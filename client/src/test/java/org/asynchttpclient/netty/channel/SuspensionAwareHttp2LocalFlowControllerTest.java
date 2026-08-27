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
package org.asynchttpclient.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.concurrent.EventExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.netty.handler.codec.http2.Http2CodecUtil.CONNECTION_STREAM_ID;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SuspensionAwareHttp2LocalFlowControllerTest {

    private static final int STREAM_ID = 1;
    private static final int SECOND_STREAM_ID = 3;
    private static final int WINDOW_UPDATE_SIZE = DEFAULT_WINDOW_SIZE / 2 + 1;

    private final Http2FrameWriter frameWriter = mock(Http2FrameWriter.class);
    private final ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    private final ChannelPromise promise = mock(ChannelPromise.class);
    private final EventExecutor executor = mock(EventExecutor.class);

    private Http2Connection connection;
    private SuspensionAwareHttp2LocalFlowController controller;

    @BeforeEach
    public void setUp() throws Http2Exception {
        reset(frameWriter, ctx, promise, executor);
        when(ctx.newPromise()).thenReturn(promise);
        when(ctx.executor()).thenReturn(executor);
        when(executor.inEventLoop()).thenReturn(true);

        connection = new DefaultHttp2Connection(false);
        controller = new SuspensionAwareHttp2LocalFlowController(connection).frameWriter(frameWriter);
        connection.local().flowController(controller);
        connection.local().createStream(STREAM_ID, false);
        connection.local().createStream(SECOND_STREAM_ID, false);
        controller.channelHandlerContext(ctx);
    }

    @Test
    public void retainsNormalConnectionAccountingWithoutSuspension() throws Http2Exception {
        receive(STREAM_ID, WINDOW_UPDATE_SIZE);

        assertEquals(WINDOW_UPDATE_SIZE, controller.unconsumedBytes(connection.connectionStream()));
        assertEquals(0, controller.autoConsumedConnectionBytes());
        verifyNoWindowUpdate();

        assertTrue(controller.consumeBytes(stream(STREAM_ID), WINDOW_UPDATE_SIZE));
        verifyWindowUpdate(CONNECTION_STREAM_ID, WINDOW_UPDATE_SIZE);
        verifyWindowUpdate(STREAM_ID, WINDOW_UPDATE_SIZE);
    }

    @Test
    public void refillsOnlyConnectionCreditWhileSuspended() throws Http2Exception {
        controller.suspendResponse();
        receive(STREAM_ID, WINDOW_UPDATE_SIZE);

        assertEquals(0, controller.unconsumedBytes(connection.connectionStream()));
        assertEquals(WINDOW_UPDATE_SIZE, controller.autoConsumedConnectionBytes());
        assertEquals(WINDOW_UPDATE_SIZE, controller.unconsumedBytes(stream(STREAM_ID)));
        verifyWindowUpdate(CONNECTION_STREAM_ID, WINDOW_UPDATE_SIZE);
        verifyNoWindowUpdate(STREAM_ID);

        assertTrue(controller.consumeBytes(stream(STREAM_ID), WINDOW_UPDATE_SIZE));
        assertEquals(0, controller.autoConsumedConnectionBytes());
        verifyWindowUpdate(STREAM_ID, WINDOW_UPDATE_SIZE);
    }

    @Test
    public void refillsCreditReceivedBeforeSuspendCallback() throws Http2Exception {
        receive(STREAM_ID, WINDOW_UPDATE_SIZE);
        verifyNoWindowUpdate();

        controller.suspendResponse();

        assertEquals(0, controller.unconsumedBytes(connection.connectionStream()));
        assertEquals(WINDOW_UPDATE_SIZE, controller.autoConsumedConnectionBytes());
        verifyWindowUpdate(CONNECTION_STREAM_ID, WINDOW_UPDATE_SIZE);
        verifyNoWindowUpdate(STREAM_ID);
    }

    @Test
    public void continuesRefillingUntilLastSuspendedResponseResumes() throws Http2Exception {
        controller.suspendResponse();
        controller.suspendResponse();
        controller.resumeResponse();
        assertTrue(controller.hasSuspendedResponse());

        receive(STREAM_ID, WINDOW_UPDATE_SIZE);
        verifyWindowUpdate(CONNECTION_STREAM_ID, WINDOW_UPDATE_SIZE);
        controller.consumeBytes(stream(STREAM_ID), WINDOW_UPDATE_SIZE);

        controller.resumeResponse();
        assertFalse(controller.hasSuspendedResponse());
        reset(frameWriter);

        receive(SECOND_STREAM_ID, WINDOW_UPDATE_SIZE);
        verifyNoWindowUpdate();
        controller.consumeBytes(stream(SECOND_STREAM_ID), WINDOW_UPDATE_SIZE);
        verifyWindowUpdate(CONNECTION_STREAM_ID, WINDOW_UPDATE_SIZE);
        verifyWindowUpdate(SECOND_STREAM_ID, WINDOW_UPDATE_SIZE);
    }

    @Test
    public void doesNotReturnConnectionCreditTwiceAfterResume() throws Http2Exception {
        controller.suspendResponse();
        receive(STREAM_ID, WINDOW_UPDATE_SIZE);
        controller.resumeResponse();
        receive(SECOND_STREAM_ID, WINDOW_UPDATE_SIZE);
        reset(frameWriter);

        controller.consumeBytes(stream(SECOND_STREAM_ID), WINDOW_UPDATE_SIZE);
        verifyNoWindowUpdate(CONNECTION_STREAM_ID);
        verifyWindowUpdate(SECOND_STREAM_ID, WINDOW_UPDATE_SIZE);

        controller.consumeBytes(stream(STREAM_ID), WINDOW_UPDATE_SIZE);
        assertEquals(0, controller.autoConsumedConnectionBytes());
        verifyWindowUpdate(CONNECTION_STREAM_ID, WINDOW_UPDATE_SIZE);
        verifyWindowUpdate(STREAM_ID, WINDOW_UPDATE_SIZE);
    }

    private void receive(int streamId, int size) throws Http2Exception {
        ByteBuf data = Unpooled.buffer(size).writerIndex(size);
        try {
            controller.receiveFlowControlledFrame(stream(streamId), data, 0, false);
        } finally {
            data.release();
        }
    }

    private Http2Stream stream(int streamId) {
        return connection.stream(streamId);
    }

    private void verifyWindowUpdate(int streamId, int increment) {
        verify(frameWriter).writeWindowUpdate(ctx, streamId, increment, promise);
    }

    private void verifyNoWindowUpdate(int streamId) {
        verify(frameWriter, never()).writeWindowUpdate(eq(ctx), eq(streamId), anyInt(), eq(promise));
    }

    private void verifyNoWindowUpdate() {
        verify(frameWriter, never()).writeWindowUpdate(any(), anyInt(), anyInt(), any());
    }
}
