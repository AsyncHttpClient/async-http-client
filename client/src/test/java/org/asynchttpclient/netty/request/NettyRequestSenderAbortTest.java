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

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the ordering inside {@link NettyRequestSender#abort(io.netty.channel.Channel, NettyResponseFuture,
 * Throwable)}: the future is completed with the caller's cause before the channel is closed.
 *
 * <p>Closing first is not inert. Once the connect path publishes the channel on the future (issue #2189), a
 * request timeout closes a socket whose TLS handshake is still in flight; that failure races back through
 * {@code NettyConnectListener.onFailure}, which aborts the same future with a {@link ConnectException}. If
 * the close runs first that cause can win, and a request timeout surfaces as a connect error instead of a
 * {@link TimeoutException}. An {@link EmbeddedChannel} makes this deterministic - it runs close listeners
 * inline, so the induced abort always beats a subsequent one.
 */
class NettyRequestSenderAbortTest {

    private AsyncHttpClientConfig config;
    private ChannelManager channelManager;
    private NettyRequestSender sender;
    private Timer timer;

    @BeforeEach
    void setUp() {
        config = config().build();
        timer = new HashedWheelTimer();
        channelManager = new ChannelManager(config, timer);
        sender = new NettyRequestSender(config, channelManager, timer, null);
    }

    @AfterEach
    void tearDown() {
        if (channelManager != null) {
            channelManager.close();
        }
        if (timer != null) {
            timer.stop();
        }
    }

    private NettyResponseFuture<Object> newFuture() {
        Request request = new RequestBuilder().setUrl("https://example.com:12345").build();
        return new NettyResponseFuture<>(request, new AsyncCompletionHandler<Object>() {
            @Override
            public Object onCompleted(Response response) {
                return null;
            }
        }, null, 0, ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
    }

    @Test
    void abortCauseSurvivesAnAbortInducedByTheCloseItTriggers() throws Exception {
        NettyResponseFuture<Object> future = newFuture();
        EmbeddedChannel channel = new EmbeddedChannel();
        future.attachChannel(channel, false);
        // Stand in for the TLS handshake failing because we closed the channel, which NettyConnectListener
        // reports by aborting this same future with a ConnectException.
        channel.closeFuture().addListener(f -> future.abort(new ConnectException("closed mid-handshake")));

        TimeoutException requestTimeout = new TimeoutException("Request timeout to example.com:12345 after 300 ms");
        try {
            sender.abort(channel, future, requestTimeout);

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertSame(requestTimeout, thrown.getCause(),
                    "the caller's cause must win over the one induced by the close it triggered");
            assertFalse(channel.isOpen(), "abort must still close the channel");
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
