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
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpClientState;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.NettyConnectListener;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@ExtendWith(NettyLeakDetectorExtension.class)
class NettyConnectListenerReplayTest {

    private AsyncHttpClientConfig config;
    private ChannelManager channelManager;
    private NettyRequestSender requestSender;
    private Timer timer;

    @BeforeEach
    void setUp() {
        config = config().setMaxRequestRetry(5).build();
        timer = new HashedWheelTimer();
        channelManager = new ChannelManager(config, timer);
        requestSender = new NettyRequestSender(config, channelManager, timer, mock(AsyncHttpClientState.class));
    }

    @AfterEach
    void tearDown() {
        channelManager.close();
        timer.stop();
    }

    // No call site reports a failure after the write today. One that did would otherwise resend the request,
    // and with retries left the caller would see a 200 for a body the server received twice.
    @Test
    void aFailureReportedAfterTheWriteIsNotReplayed() {
        RetryCountingHandler handler = new RetryCountingHandler();
        Request request = new RequestBuilder("POST").setUrl("http://example.com:12345").setBody("body").build();
        NettyResponseFuture<Response> future = new NettyResponseFuture<>(request, handler,
                new NettyRequestFactory(config).newNettyRequest(request, false, null, null, null), 5,
                ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
        future.setTimeoutsHolder(new TimeoutsHolder(null, future, null, config, null));

        NettyConnectListener<Response> listener = new NettyConnectListener<>(future, requestSender, channelManager, null);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 12345));
            assertTrue(channel.outboundMessages().size() > 0, "fixture: the request must have been written");

            // A refused connect, which is a failure onFailure does replay while the request is unwritten.
            ConnectException refused = new ConnectException("Connection refused: /127.0.0.1:12345");
            refused.initCause(new ConnectException("Connection refused"));
            listener.onFailure(channel, refused);

            assertEquals(0, handler.retries.get(), "a written request was replayed");
            ExecutionException e = assertThrows(ExecutionException.class, future::get);
            assertInstanceOf(ConnectException.class, e.getCause());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static final class RetryCountingHandler extends AsyncCompletionHandler<Response> {

        private final AtomicInteger retries = new AtomicInteger();

        @Override
        public void onRetry() {
            retries.incrementAndGet();
        }

        @Override
        public Response onCompleted(Response response) {
            return response;
        }
    }
}
