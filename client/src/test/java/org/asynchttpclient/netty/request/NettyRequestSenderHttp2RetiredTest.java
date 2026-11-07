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
package org.asynchttpclient.netty.request;

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpClientState;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Http2ConnectionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A request that looked up an HTTP/2 connection just before the connection was retired at {@code connectionTtl},
 * and then found it closing or full, has written nothing, so it is sent again on another connection instead of
 * failing. It is sent to a port nobody listens on: a {@link ConnectException} is the proof that the request was
 * re-dispatched to a new connection, where failing in place would surface the retirement {@link IOException}.
 * {@code maxRequestRetry} is 0 throughout, because the re-dispatch must not depend on the retry budget.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
class NettyRequestSenderHttp2RetiredTest {

    private AsyncHttpClientConfig config;
    private Timer timer;
    private ChannelManager channelManager;
    private NettyRequestSender sender;
    private EmbeddedChannel parent;
    private int closedPort;

    @BeforeEach
    void setUp() throws IOException {
        config = config().setMaxRequestRetry(0).build();
        timer = new HashedWheelTimer();
        channelManager = new ChannelManager(config, timer);
        sender = new NettyRequestSender(config, channelManager, timer, mock(AsyncHttpClientState.class));
        channelManager.configureBootstraps(sender);
        parent = new EmbeddedChannel();
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
    }

    @AfterEach
    void tearDown() {
        parent.finishAndReleaseAll();
        channelManager.close();
        timer.stop();
    }

    private Http2ConnectionState http2State() {
        Http2ConnectionState state = new Http2ConnectionState();
        parent.attr(Http2ConnectionState.HTTP2_STATE_KEY).set(state);
        return state;
    }

    private NettyResponseFuture<Response> futureFor(Request request) {
        return new NettyResponseFuture<>(request, new AsyncCompletionHandlerBase(),
                new NettyRequestFactory(config).newNettyRequest(request, false, null, null, null), 0,
                ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
    }

    private Request get() {
        return new RequestBuilder("GET").setUrl("http://127.0.0.1:" + closedPort + "/").build();
    }

    private static Throwable failureOf(NettyResponseFuture<Response> future) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        return failure.getCause();
    }

    @Test
    void requestLosingTheIdleCloseIsSentOnAnotherConnection() {
        Http2ConnectionState state = http2State();
        state.retire();
        assertTrue(state.closeIfRetiredAndIdle(), "the connection was closed before the request got a stream");
        NettyResponseFuture<Response> future = futureFor(get());

        sender.writeRequest(future, parent);

        assertInstanceOf(ConnectException.class, failureOf(future),
                "the request must have been sent again on a new connection");
    }

    @Test
    void requestFindingTheRetiredConnectionFullIsSentOnAnotherConnection() {
        Http2ConnectionState state = http2State();
        state.setClientMaxConcurrentStreams(1);
        assertTrue(state.tryAcquireStream());
        state.retire();
        NettyResponseFuture<Response> future = futureFor(get());

        sender.writeRequest(future, parent);

        assertInstanceOf(ConnectException.class, failureOf(future),
                "a full retired connection must not queue the request; it goes to a new connection");
        assertEquals(1, state.getActiveStreams(), "nothing was queued on the retired connection");
    }

    @Test
    void requestIsSentAgainOnlyOnce() {
        Http2ConnectionState state = http2State();
        state.retire();
        assertTrue(state.closeIfRetiredAndIdle());
        NettyResponseFuture<Response> future = futureFor(get());
        assertTrue(future.claimHttp2Redispatch(), "stands in for an earlier re-dispatch of the same exchange");
        assertFalse(future.claimHttp2Redispatch());

        sender.writeRequest(future, parent);

        Throwable cause = failureOf(future);
        assertInstanceOf(IOException.class, cause);
        assertFalse(cause instanceof ConnectException, "a second refusal must fail, not be sent yet again");
    }

    @Test
    void reDispatchedRequestBodyIsReleased() {
        // Leak-checked by the extension: the body built for the refused attempt and the one rebuilt for the
        // re-dispatch must both be released, and the caller's own buffer must be left to the caller.
        ByteBuf body = ByteBufAllocator.DEFAULT.heapBuffer();
        body.writeCharSequence("payload", StandardCharsets.US_ASCII);
        try {
            Http2ConnectionState state = http2State();
            state.retire();
            assertTrue(state.closeIfRetiredAndIdle());
            Request request = new RequestBuilder("POST").setUrl("http://127.0.0.1:" + closedPort + "/")
                    .setBody(body).build();
            NettyResponseFuture<Response> future = futureFor(request);

            sender.writeRequest(future, parent);

            assertInstanceOf(ConnectException.class, failureOf(future));
            assertEquals(1, body.refCnt(), "the caller's buffer still belongs to the caller");
        } finally {
            body.release();
        }
    }
}
