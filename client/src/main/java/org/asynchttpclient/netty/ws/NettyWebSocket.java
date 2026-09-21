/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.ws;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.ws.WebSocket;
import org.asynchttpclient.ws.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;

import static io.netty.buffer.Unpooled.wrappedBuffer;

public final class NettyWebSocket implements WebSocket {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyWebSocket.class);

    private final Channel channel;
    private final HttpHeaders upgradeHeaders;
    private final Collection<WebSocketListener> listeners;
    private FragmentedFrameType expectedFragmentedFrameType;
    // no need for volatile because only mutated in IO thread
    private boolean ready;
    // written in the IO thread, but isOpen() reads it from any
    private volatile boolean closing;
    private List<WebSocketFrame> bufferedFrames;

    public NettyWebSocket(Channel channel, HttpHeaders upgradeHeaders) {
        this(channel, upgradeHeaders, new ConcurrentLinkedQueue<>());
    }

    private NettyWebSocket(Channel channel, HttpHeaders upgradeHeaders, Collection<WebSocketListener> listeners) {
        this.channel = channel;
        this.upgradeHeaders = upgradeHeaders;
        this.listeners = listeners;
    }

    @Override
    public HttpHeaders getUpgradeHeaders() {
        return upgradeHeaders;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return channel.remoteAddress();
    }

    @Override
    public SocketAddress getLocalAddress() {
        return channel.localAddress();
    }

    @Override
    public Future<Void> sendTextFrame(String message) {
        return sendTextFrame(message, true, 0);
    }

    @Override
    public Future<Void> sendTextFrame(String payload, boolean finalFragment, int rsv) {
        return send(new TextWebSocketFrame(finalFragment, rsv, payload));
    }

    @Override
    public Future<Void> sendTextFrame(ByteBuf payload, boolean finalFragment, int rsv) {
        return send(new TextWebSocketFrame(finalFragment, rsv, payload));
    }

    @Override
    public Future<Void> sendBinaryFrame(byte[] payload) {
        return sendBinaryFrame(payload, true, 0);
    }

    @Override
    public Future<Void> sendBinaryFrame(byte[] payload, boolean finalFragment, int rsv) {
        return sendBinaryFrame(wrappedBuffer(payload), finalFragment, rsv);
    }

    @Override
    public Future<Void> sendBinaryFrame(ByteBuf payload, boolean finalFragment, int rsv) {
        return send(new BinaryWebSocketFrame(finalFragment, rsv, payload));
    }

    @Override
    public Future<Void> sendContinuationFrame(String payload, boolean finalFragment, int rsv) {
        return send(new ContinuationWebSocketFrame(finalFragment, rsv, payload));
    }

    @Override
    public Future<Void> sendContinuationFrame(byte[] payload, boolean finalFragment, int rsv) {
        return sendContinuationFrame(wrappedBuffer(payload), finalFragment, rsv);
    }

    @Override
    public Future<Void> sendContinuationFrame(ByteBuf payload, boolean finalFragment, int rsv) {
        return send(new ContinuationWebSocketFrame(finalFragment, rsv, payload));
    }

    @Override
    public Future<Void> sendPingFrame() {
        return send(new PingWebSocketFrame());
    }

    @Override
    public Future<Void> sendPingFrame(byte[] payload) {
        return sendPingFrame(wrappedBuffer(payload));
    }

    @Override
    public Future<Void> sendPingFrame(ByteBuf payload) {
        return send(new PingWebSocketFrame(payload));
    }

    @Override
    public Future<Void> sendPongFrame() {
        return send(new PongWebSocketFrame());
    }

    @Override
    public Future<Void> sendPongFrame(byte[] payload) {
        return sendPongFrame(wrappedBuffer(payload));
    }

    @Override
    public Future<Void> sendPongFrame(ByteBuf payload) {
        return send(new PongWebSocketFrame(wrappedBuffer(payload)));
    }

    @Override
    public Future<Void> sendCloseFrame() {
        return sendCloseFrame(1000, "normal closure");
    }

    @Override
    public Future<Void> sendCloseFrame(int statusCode, String reasonText) {
        if (isOpen()) {
            return send(new CloseWebSocketFrame(statusCode, reasonText));
        }
        return ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
    }

    // Writes are decided on the event loop, so one issued once the close has begun always fails the same way.
    // Left to the pipeline it fails with whatever the SslHandler or the socket happens to report. A listener
    // may still send from inside onClose, which is how it answers the peer's close frame.
    private Future<Void> send(WebSocketFrame frame) {
        EventLoop eventLoop = channel.eventLoop();
        if (eventLoop.inEventLoop()) {
            return closing ? refuse(frame) : channel.writeAndFlush(frame);
        }
        ChannelPromise promise = channel.newPromise();
        try {
            eventLoop.execute(() -> {
                if (closing) {
                    frame.release();
                    promise.setFailure(new ClosedChannelException());
                } else {
                    channel.writeAndFlush(frame, promise);
                }
            });
        } catch (RejectedExecutionException e) {
            return refuse(frame);
        }
        return promise;
    }

    private static Future<Void> refuse(WebSocketFrame frame) {
        frame.release();
        return ImmediateEventExecutor.INSTANCE.newFailedFuture(new ClosedChannelException());
    }

    @Override
    public boolean isOpen() {
        return !closing && channel.isOpen();
    }

    @Override
    public WebSocket addWebSocketListener(WebSocketListener l) {
        listeners.add(l);
        return this;
    }

    @Override
    public WebSocket removeWebSocketListener(WebSocketListener l) {
        listeners.remove(l);
        return this;
    }

    // INTERNAL, NOT FOR PUBLIC USAGE!!!

    public boolean isReady() {
        return ready;
    }

    public void bufferFrame(WebSocketFrame frame) {
        if (bufferedFrames == null) {
            bufferedFrames = new ArrayList<>(1);
        }
        frame.retain();
        bufferedFrames.add(frame);
    }

    private void releaseBufferedFrames() {
        if (bufferedFrames != null) {
            for (WebSocketFrame frame : bufferedFrames) {
                frame.release();
            }
            bufferedFrames = null;
        }
    }

    public void processBufferedFrames() {
        ready = true;
        if (bufferedFrames != null) {
            try {
                for (WebSocketFrame frame : bufferedFrames) {
                    handleFrame(frame);
                }
            } finally {
                releaseBufferedFrames();
            }
            bufferedFrames = null;
        }
    }

    public void handleFrame(WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame) {
            onTextFrame((TextWebSocketFrame) frame);

        } else if (frame instanceof BinaryWebSocketFrame) {
            onBinaryFrame((BinaryWebSocketFrame) frame);

        } else if (frame instanceof CloseWebSocketFrame) {
            Channels.setDiscard(channel);
            CloseWebSocketFrame closeFrame = (CloseWebSocketFrame) frame;
            onClose(closeFrame.statusCode(), closeFrame.reasonText());
            closing = true;
            Channels.silentlyCloseChannel(channel);

        } else if (frame instanceof PingWebSocketFrame) {
            onPingFrame((PingWebSocketFrame) frame);

        } else if (frame instanceof PongWebSocketFrame) {
            onPongFrame((PongWebSocketFrame) frame);

        } else if (frame instanceof ContinuationWebSocketFrame) {
            onContinuationFrame((ContinuationWebSocketFrame) frame);
        }
    }

    public void onError(Throwable t) {
        try {
            for (WebSocketListener listener : listeners) {
                try {
                    listener.onError(t);
                } catch (Throwable t2) {
                    LOGGER.error("WebSocketListener.onError crash", t2);
                }
            }
        } finally {
            releaseBufferedFrames();
        }
    }

    public void onClose(int code, String reason) {
        try {
            for (WebSocketListener listener : listeners) {
                try {
                    listener.onClose(this, code, reason);
                } catch (Throwable t) {
                    listener.onError(t);
                }
            }
            listeners.clear();
        } finally {
            releaseBufferedFrames();
        }
    }

    @Override
    public String toString() {
        return "NettyWebSocket{channel=" + channel + '}';
    }

    private void onBinaryFrame(BinaryWebSocketFrame frame) {
        if (expectedFragmentedFrameType == null && !frame.isFinalFragment()) {
            expectedFragmentedFrameType = FragmentedFrameType.BINARY;
        }
        onBinaryFrame0(frame);
    }

    private void onBinaryFrame0(WebSocketFrame frame) {
        byte[] bytes = ByteBufUtil.getBytes(frame.content());
        for (WebSocketListener listener : listeners) {
            listener.onBinaryFrame(bytes, frame.isFinalFragment(), frame.rsv());
        }
    }

    private void onTextFrame(TextWebSocketFrame frame) {
        if (expectedFragmentedFrameType == null && !frame.isFinalFragment()) {
            expectedFragmentedFrameType = FragmentedFrameType.TEXT;
        }
        onTextFrame0(frame);
    }

    private void onTextFrame0(WebSocketFrame frame) {
        for (WebSocketListener listener : listeners) {
            listener.onTextFrame(frame.content().toString(StandardCharsets.UTF_8), frame.isFinalFragment(), frame.rsv());
        }
    }

    private void onContinuationFrame(ContinuationWebSocketFrame frame) {
        if (expectedFragmentedFrameType == null) {
            LOGGER.warn("Received continuation frame without an original text or binary frame, ignoring");
            return;
        }
        try {
            switch (expectedFragmentedFrameType) {
                case BINARY:
                    onBinaryFrame0(frame);
                    break;
                case TEXT:
                    onTextFrame0(frame);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown FragmentedFrameType " + expectedFragmentedFrameType);
            }
        } finally {
            if (frame.isFinalFragment()) {
                expectedFragmentedFrameType = null;
            }
        }
    }

    private void onPingFrame(PingWebSocketFrame frame) {
        byte[] bytes = ByteBufUtil.getBytes(frame.content());
        for (WebSocketListener listener : listeners) {
            listener.onPingFrame(bytes);
        }
    }

    private void onPongFrame(PongWebSocketFrame frame) {
        byte[] bytes = ByteBufUtil.getBytes(frame.content());
        for (WebSocketListener listener : listeners) {
            listener.onPongFrame(bytes);
        }
    }

    private enum FragmentedFrameType {
        TEXT, BINARY
    }
}
