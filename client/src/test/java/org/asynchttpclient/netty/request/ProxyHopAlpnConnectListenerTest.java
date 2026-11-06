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
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpClientState;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.ChannelState;
import org.asynchttpclient.netty.channel.ConnectionSemaphore;
import org.asynchttpclient.netty.channel.NettyConnectListener;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A connection to an HTTPS proxy that selected h2 fails the request without a retry, since a new connection
 * would select h2 again. Other connect failures are retried once the future is past {@link ChannelState#NEW},
 * after an earlier retry or a pooled connection that turned out to be closed, so each state is checked.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
class ProxyHopAlpnConnectListenerTest {

    @ParameterizedTest
    @EnumSource(value = ChannelState.class, names = {"NEW", "RECONNECTED", "POOLED"})
    void aProxyThatSelectsHttp2FailsTheRequestWithoutARetry(ChannelState state) throws Exception {
        AsyncHttpClientConfig config = config().build();
        Request request = new RequestBuilder().setUrl("https://example.com/").build();
        ProxyServer proxy = proxyServer("localhost", 8443).setProxyType(ProxyType.HTTPS).build();
        CountingSemaphore semaphore = new CountingSemaphore();
        AtomicInteger retries = new AtomicInteger();
        NettyResponseFuture<Object> future = new NettyResponseFuture<>(request, new AsyncCompletionHandler<Object>() {
            @Override
            public void onRetry() {
                retries.incrementAndGet();
                // Refuses the retry, so that it never reaches the network.
                throw new IllegalStateException("retried");
            }

            @Override
            public Object onCompleted(Response response) {
                return null;
            }
        }, new NettyRequestFactory(config).newNettyRequest(request, true, proxy, null, null), 5,
                ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, semaphore, proxy);
        future.setChannelState(state);
        future.setTimeoutsHolder(new TimeoutsHolder(null, future, null, config, null));
        future.acquirePartitionLockLazily();

        ChannelManager channelManager = mock(ChannelManager.class);
        NettyRequestSender requestSender = new NettyRequestSender(config, channelManager, null,
                mock(AsyncHttpClientState.class));
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            SslHandler sslHandler = mock(SslHandler.class);
            SSLEngine engine = mock(SSLEngine.class);
            when(engine.getSession()).thenReturn(mock(SSLSession.class));
            when(sslHandler.engine()).thenReturn(engine);
            when(sslHandler.applicationProtocol()).thenReturn(ApplicationProtocolNames.HTTP_2);
            when(sslHandler.handshakeFuture())
                    .thenReturn(ImmediateEventExecutor.INSTANCE.<Channel>newSucceededFuture(channel));
            when(channelManager.addSslHandler(eq(channel.pipeline()), any(), isNull(), eq(false), eq(false)))
                    .thenReturn(sslHandler);

            new NettyConnectListener<>(future, requestSender, channelManager, semaphore).onSuccess(channel, null);

            assertEquals(0, retries.get(), "selecting h2 at the proxy must not be retried");
            assertTrue(future.isDone());
            ExecutionException failure = assertThrows(ExecutionException.class, future::get);
            assertInstanceOf(ConnectException.class, failure.getCause());
            assertInstanceOf(ProtocolException.class, failure.getCause().getCause());
            assertFalse(channel.isOpen());
            assertEquals(1, semaphore.acquired.get());
            assertEquals(1, semaphore.released.get(), "the permit must be returned exactly once");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static final class CountingSemaphore implements ConnectionSemaphore {

        private final AtomicInteger acquired = new AtomicInteger();
        private final AtomicInteger released = new AtomicInteger();

        @Override
        public void acquireChannelLock(Object partitionKey) {
            acquired.incrementAndGet();
        }

        @Override
        public void acquireChannelLock(Object partitionKey, boolean nonBlocking) {
            acquired.incrementAndGet();
        }

        @Override
        public void releaseChannelLock(Object partitionKey) {
            released.incrementAndGet();
        }
    }
}
