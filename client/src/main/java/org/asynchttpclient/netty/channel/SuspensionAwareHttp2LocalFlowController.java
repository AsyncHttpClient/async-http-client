/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
/*
 * Portions adapted from Netty 4.2.17.Final's DefaultHttp2LocalFlowController.
 * Copyright 2014 The Netty Project, licensed under Apache License 2.0.
 */
package org.asynchttpclient.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionAdapter;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Exception.CompositeStreamException;
import io.netty.handler.codec.http2.Http2Exception.StreamException;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.Http2StreamVisitor;
import io.netty.util.AttributeKey;
import io.netty.util.internal.PlatformDependent;

import static io.netty.handler.codec.http2.Http2CodecUtil.CONNECTION_STREAM_ID;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_INITIAL_WINDOW_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.MIN_INITIAL_WINDOW_SIZE;
import static io.netty.handler.codec.http2.Http2Error.FLOW_CONTROL_ERROR;
import static io.netty.handler.codec.http2.Http2Error.INTERNAL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.connectionError;
import static io.netty.handler.codec.http2.Http2Exception.streamError;
import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Netty's default local HTTP/2 flow controller adapted to refill connection credit only while at least one response
 * on the connection is suspended. Stream credit remains consumption-driven at all times.
 *
 * <p>The implementation follows {@code DefaultHttp2LocalFlowController} as released in Netty 4.2.17.Final. Netty's
 * connection auto-refill state is private and fixed at construction time, so it cannot be enabled only for the
 * lifetime of a suspended response by composition or subclassing. Because AHC owns this package-private adaptation,
 * Netty upgrades must compare it with the corresponding upstream implementation for correctness and security fixes.
 * An upstream API for changing connection auto-refill at runtime would allow this class to be removed.</p>
 *
 * <p>This class is not thread safe. All methods are invoked on the HTTP/2 connection event loop.</p>
 */
final class SuspensionAwareHttp2LocalFlowController implements Http2LocalFlowController {

    static final AttributeKey<SuspensionAwareHttp2LocalFlowController> CHANNEL_KEY =
            AttributeKey.valueOf(SuspensionAwareHttp2LocalFlowController.class, "controller");

    private static final float DEFAULT_WINDOW_UPDATE_RATIO = 0.5f;

    private final Http2Connection connection;
    private final Http2Connection.PropertyKey stateKey;
    private Http2FrameWriter frameWriter;
    private ChannelHandlerContext ctx;
    private float windowUpdateRatio;
    private int initialWindowSize = DEFAULT_WINDOW_SIZE;
    private int suspendedResponses;

    SuspensionAwareHttp2LocalFlowController(Http2Connection connection) {
        this.connection = checkNotNull(connection, "connection");
        windowUpdateRatio(DEFAULT_WINDOW_UPDATE_RATIO);

        stateKey = connection.newKey();
        connection.connectionStream().setProperty(
                stateKey, new SuspensionAwareConnectionState(connection.connectionStream(), initialWindowSize));

        connection.addListener(new Http2ConnectionAdapter() {
            @Override
            public void onStreamAdded(Http2Stream stream) {
                stream.setProperty(stateKey, REDUCED_FLOW_STATE);
            }

            @Override
            public void onStreamActive(Http2Stream stream) {
                stream.setProperty(stateKey, new DefaultState(stream, initialWindowSize));
            }

            @Override
            public void onStreamClosed(Http2Stream stream) {
                try {
                    FlowState state = state(stream);
                    int unconsumedBytes = state.unconsumedBytes();
                    if (ctx != null && unconsumedBytes > 0 && consumeAllBytes(state, unconsumedBytes)) {
                        ctx.flush();
                    }
                } catch (Http2Exception e) {
                    PlatformDependent.throwException(e);
                } finally {
                    stream.setProperty(stateKey, REDUCED_FLOW_STATE);
                }
            }
        });
    }

    void suspendResponse() throws Http2Exception {
        assert ctx != null && ctx.executor().inEventLoop();
        if (suspendedResponses == 0) {
            connectionState().startAutoRefill();
        }
        suspendedResponses++;
    }

    void resumeResponse() {
        assert ctx != null && ctx.executor().inEventLoop();
        if (suspendedResponses <= 0) {
            throw new IllegalStateException("No suspended HTTP/2 response to resume");
        }
        suspendedResponses--;
    }

    boolean hasSuspendedResponse() {
        return suspendedResponses > 0;
    }

    long autoConsumedConnectionBytes() {
        return connectionState().autoConsumedBytes;
    }

    @Override
    public SuspensionAwareHttp2LocalFlowController frameWriter(Http2FrameWriter frameWriter) {
        this.frameWriter = checkNotNull(frameWriter, "frameWriter");
        return this;
    }

    @Override
    public void channelHandlerContext(ChannelHandlerContext ctx) {
        this.ctx = checkNotNull(ctx, "ctx");
    }

    @Override
    public void initialWindowSize(int newWindowSize) throws Http2Exception {
        assert ctx == null || ctx.executor().inEventLoop();
        int delta = newWindowSize - initialWindowSize;
        initialWindowSize = newWindowSize;

        WindowUpdateVisitor visitor = new WindowUpdateVisitor(delta);
        connection.forEachActiveStream(visitor);
        visitor.throwIfError();
    }

    @Override
    public int initialWindowSize() {
        return initialWindowSize;
    }

    @Override
    public int windowSize(Http2Stream stream) {
        return state(stream).windowSize();
    }

    @Override
    public int initialWindowSize(Http2Stream stream) {
        return state(stream).initialWindowSize();
    }

    @Override
    public void incrementWindowSize(Http2Stream stream, int delta) throws Http2Exception {
        assert ctx != null && ctx.executor().inEventLoop();
        FlowState state = state(stream);
        state.incrementInitialStreamWindow(delta);
        state.writeWindowUpdateIfNeeded();
    }

    @Override
    public boolean consumeBytes(Http2Stream stream, int numBytes) throws Http2Exception {
        assert ctx != null && ctx.executor().inEventLoop();
        checkPositiveOrZero(numBytes, "numBytes");
        if (numBytes == 0) {
            return false;
        }

        if (stream != null && !isClosed(stream)) {
            if (stream.id() == CONNECTION_STREAM_ID) {
                throw new UnsupportedOperationException("Returning bytes for the connection window is not supported");
            }
            return consumeAllBytes(state(stream), numBytes);
        }
        return false;
    }

    private boolean consumeAllBytes(FlowState state, int numBytes) throws Http2Exception {
        return connectionState().consumeBytes(numBytes) | state.consumeBytes(numBytes);
    }

    @Override
    public int unconsumedBytes(Http2Stream stream) {
        return state(stream).unconsumedBytes();
    }

    private static void checkValidRatio(float ratio) {
        if (Double.compare(ratio, 0.0) <= 0 || Double.compare(ratio, 1.0) >= 0) {
            throw new IllegalArgumentException("Invalid ratio: " + ratio);
        }
    }

    public void windowUpdateRatio(float ratio) {
        assert ctx == null || ctx.executor().inEventLoop();
        checkValidRatio(ratio);
        windowUpdateRatio = ratio;
    }

    public float windowUpdateRatio() {
        return windowUpdateRatio;
    }

    public void windowUpdateRatio(Http2Stream stream, float ratio) throws Http2Exception {
        assert ctx != null && ctx.executor().inEventLoop();
        checkValidRatio(ratio);
        FlowState state = state(stream);
        state.windowUpdateRatio(ratio);
        state.writeWindowUpdateIfNeeded();
    }

    public float windowUpdateRatio(Http2Stream stream) throws Http2Exception {
        return state(stream).windowUpdateRatio();
    }

    @Override
    public void receiveFlowControlledFrame(Http2Stream stream, ByteBuf data, int padding,
                                           boolean endOfStream) throws Http2Exception {
        assert ctx != null && ctx.executor().inEventLoop();
        int dataLength = data.readableBytes() + padding;

        SuspensionAwareConnectionState connectionState = connectionState();
        connectionState.receiveFlowControlledFrame(dataLength);

        if (stream != null && !isClosed(stream)) {
            FlowState state = state(stream);
            state.endOfStream(endOfStream);
            state.receiveFlowControlledFrame(dataLength);
        } else if (dataLength > 0) {
            connectionState.consumeBytes(dataLength);
        }
    }

    private SuspensionAwareConnectionState connectionState() {
        return connection.connectionStream().getProperty(stateKey);
    }

    private FlowState state(Http2Stream stream) {
        return stream.getProperty(stateKey);
    }

    private static boolean isClosed(Http2Stream stream) {
        return stream.state() == Http2Stream.State.CLOSED;
    }

    private final class SuspensionAwareConnectionState extends DefaultState {

        private long autoConsumedBytes;

        SuspensionAwareConnectionState(Http2Stream stream, int initialWindowSize) {
            super(stream, initialWindowSize);
        }

        void startAutoRefill() throws Http2Exception {
            // DATA may already have reached a stream child channel before its handler calls suspend(). Return that
            // outstanding connection credit too, otherwise the pre-suspension bytes could still starve siblings.
            int unconsumedBytes = unconsumedBytes();
            if (unconsumedBytes > 0) {
                if (super.consumeBytes(unconsumedBytes)) {
                    ctx.flush();
                }
                autoConsumedBytes += unconsumedBytes;
            }
        }

        @Override
        public void receiveFlowControlledFrame(int dataLength) throws Http2Exception {
            super.receiveFlowControlledFrame(dataLength);
            if (hasSuspendedResponse() && dataLength > 0) {
                super.consumeBytes(dataLength);
                autoConsumedBytes += dataLength;
            }
        }

        @Override
        public boolean consumeBytes(int numBytes) throws Http2Exception {
            int alreadyConsumed = (int) min(autoConsumedBytes, (long) numBytes);
            autoConsumedBytes -= alreadyConsumed;
            int remaining = numBytes - alreadyConsumed;
            return remaining > 0 && super.consumeBytes(remaining);
        }
    }

    private class DefaultState implements FlowState {

        private final Http2Stream stream;
        private int window;
        private int processedWindow;
        private int initialStreamWindowSize;
        private float streamWindowUpdateRatio;
        private int lowerBound;
        private boolean endOfStream;

        DefaultState(Http2Stream stream, int initialWindowSize) {
            this.stream = stream;
            window(initialWindowSize);
            streamWindowUpdateRatio = windowUpdateRatio;
        }

        @Override
        public void window(int initialWindowSize) {
            assert ctx == null || ctx.executor().inEventLoop();
            window = processedWindow = initialStreamWindowSize = initialWindowSize;
        }

        @Override
        public int windowSize() {
            return window;
        }

        @Override
        public int initialWindowSize() {
            return initialStreamWindowSize;
        }

        @Override
        public void endOfStream(boolean endOfStream) {
            this.endOfStream = endOfStream;
        }

        @Override
        public float windowUpdateRatio() {
            return streamWindowUpdateRatio;
        }

        @Override
        public void windowUpdateRatio(float ratio) {
            assert ctx == null || ctx.executor().inEventLoop();
            streamWindowUpdateRatio = ratio;
        }

        @Override
        public void incrementInitialStreamWindow(int delta) {
            int newValue = (int) min(MAX_INITIAL_WINDOW_SIZE,
                    max(MIN_INITIAL_WINDOW_SIZE, initialStreamWindowSize + (long) delta));
            initialStreamWindowSize += newValue - initialStreamWindowSize;
        }

        @Override
        public void incrementFlowControlWindows(int delta) throws Http2Exception {
            if (delta > 0 && window > MAX_INITIAL_WINDOW_SIZE - delta) {
                throw streamError(stream.id(), FLOW_CONTROL_ERROR,
                        "Flow control window overflowed for stream: %d", stream.id());
            }
            window += delta;
            processedWindow += delta;
            lowerBound = min(delta, 0);
        }

        @Override
        public void receiveFlowControlledFrame(int dataLength) throws Http2Exception {
            assert dataLength >= 0;
            window -= dataLength;
            if (window < lowerBound) {
                throw streamError(stream.id(), FLOW_CONTROL_ERROR,
                        "Flow control window exceeded for stream: %d", stream.id());
            }
        }

        private void returnProcessedBytes(int delta) throws Http2Exception {
            if (processedWindow - delta < window) {
                throw streamError(stream.id(), INTERNAL_ERROR,
                        "Attempting to return too many bytes for stream %d", stream.id());
            }
            processedWindow -= delta;
        }

        @Override
        public boolean consumeBytes(int numBytes) throws Http2Exception {
            returnProcessedBytes(numBytes);
            return writeWindowUpdateIfNeeded();
        }

        @Override
        public int unconsumedBytes() {
            return processedWindow - window;
        }

        @Override
        public boolean writeWindowUpdateIfNeeded() throws Http2Exception {
            if (endOfStream || initialStreamWindowSize <= 0 || isClosed(stream)) {
                return false;
            }
            int threshold = (int) (initialStreamWindowSize * streamWindowUpdateRatio);
            if (processedWindow <= threshold) {
                writeWindowUpdate();
                return true;
            }
            return false;
        }

        private void writeWindowUpdate() throws Http2Exception {
            int deltaWindowSize = initialStreamWindowSize - processedWindow;
            try {
                incrementFlowControlWindows(deltaWindowSize);
            } catch (Throwable t) {
                throw connectionError(INTERNAL_ERROR, t,
                        "Attempting to return too many bytes for stream %d", stream.id());
            }
            frameWriter.writeWindowUpdate(ctx, stream.id(), deltaWindowSize, ctx.newPromise());
        }
    }

    private static final FlowState REDUCED_FLOW_STATE = new FlowState() {
        @Override
        public int windowSize() {
            return 0;
        }

        @Override
        public int initialWindowSize() {
            return 0;
        }

        @Override
        public void window(int initialWindowSize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void incrementInitialStreamWindow(int delta) {
            // Required while the peer has not yet acknowledged the stream as active.
        }

        @Override
        public boolean writeWindowUpdateIfNeeded() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean consumeBytes(int numBytes) {
            return false;
        }

        @Override
        public int unconsumedBytes() {
            return 0;
        }

        @Override
        public float windowUpdateRatio() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void windowUpdateRatio(float ratio) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void receiveFlowControlledFrame(int dataLength) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void incrementFlowControlWindows(int delta) {
            // Required while the peer has not yet acknowledged the stream as active.
        }

        @Override
        public void endOfStream(boolean endOfStream) {
            throw new UnsupportedOperationException();
        }
    };

    private interface FlowState {
        int windowSize();

        int initialWindowSize();

        void window(int initialWindowSize);

        void incrementInitialStreamWindow(int delta);

        boolean writeWindowUpdateIfNeeded() throws Http2Exception;

        boolean consumeBytes(int numBytes) throws Http2Exception;

        int unconsumedBytes();

        float windowUpdateRatio();

        void windowUpdateRatio(float ratio);

        void receiveFlowControlledFrame(int dataLength) throws Http2Exception;

        void incrementFlowControlWindows(int delta) throws Http2Exception;

        void endOfStream(boolean endOfStream);
    }

    private final class WindowUpdateVisitor implements Http2StreamVisitor {

        private final int delta;
        private CompositeStreamException compositeException;

        WindowUpdateVisitor(int delta) {
            this.delta = delta;
        }

        @Override
        public boolean visit(Http2Stream stream) throws Http2Exception {
            try {
                FlowState state = state(stream);
                state.incrementFlowControlWindows(delta);
                state.incrementInitialStreamWindow(delta);
            } catch (StreamException e) {
                if (compositeException == null) {
                    compositeException = new CompositeStreamException(e.error(), 4);
                }
                compositeException.add(e);
            }
            return true;
        }

        void throwIfError() throws CompositeStreamException {
            if (compositeException != null) {
                throw compositeException;
            }
        }
    }
}
