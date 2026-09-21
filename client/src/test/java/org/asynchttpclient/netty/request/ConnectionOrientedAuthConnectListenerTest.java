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
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.ConnectionSemaphore;
import org.asynchttpclient.netty.channel.NettyConnectListener;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * h2c has no ALPN to refuse h2 for us, so the upgrade decision itself has to carry it.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
class ConnectionOrientedAuthConnectListenerTest {

    private static final String H2C_URL = "http://example.com:12345";

    private AsyncHttpClientConfig h2cConfig;
    private ChannelManager channelManager;
    private NettyRequestSender requestSender;
    private Timer timer;

    @BeforeEach
    void setUp() {
        h2cConfig = config().setHttp2Enabled(true).setHttp2CleartextEnabled(true).build();
        timer = new HashedWheelTimer();
        channelManager = new ChannelManager(h2cConfig, timer);
        // The listener writes the request once the connection is up, so it needs a real sender; the write
        // lands in the EmbeddedChannel and is discarded with it.
        requestSender = new NettyRequestSender(h2cConfig, channelManager, timer, null);
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

    @Test
    void anNtlmRealmKeepsTheCleartextConnectionOffTheHttp2Registry() throws Exception {
        CountingSemaphore semaphore = new CountingSemaphore();
        NettyResponseFuture<Object> future = newFuture(semaphore, realm(Realm.AuthScheme.NTLM, "alice"), null);
        future.acquirePartitionLockLazily();

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, requestSender, channelManager, semaphore);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 12345));

            assertNull(channel.pipeline().get(ChannelManager.HTTP2_FRAME_CODEC),
                    "the connection must stay on HTTP/1.1: upgrading it and then hiding it would strand the "
                            + "socket and break the NTLM 401 retry, which needs the socket that issued the challenge");
            assertNull(channelManager.pollHttp2Connection(future.getPartitionKey()),
                    "a socket NTLM authenticates must not be published for another principal to multiplex onto");
            assertEquals(0, semaphore.released.get(),
                    "the exchange still holds its permit; the point is that it is releasable, not released");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * The proxy hop authenticates the same socket, so a realm-less request through an NTLM proxy is the case
     * that matters: without this the connection is registered and any later request draws it.
     */
    @Test
    void anNtlmProxyRealmAlsoKeepsItOff() throws Exception {
        CountingSemaphore semaphore = new CountingSemaphore();
        NettyResponseFuture<Object> future = newFuture(semaphore, null, realm(Realm.AuthScheme.NTLM, "alice"));
        future.acquirePartitionLockLazily();

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, requestSender, channelManager, semaphore);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 12345));
            assertNull(channel.pipeline().get(ChannelManager.HTTP2_FRAME_CODEC),
                    "the proxy hop authenticates the same socket, so this must stay on HTTP/1.1 too: the 407 "
                            + "retry needs the socket that issued the challenge");
            assertNull(channelManager.pollHttp2Connection(future.getPartitionKey()),
                    "a tunnel the proxy authenticated must not be shared either");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * The control: without such a realm the connection is still registered, so the guard has not simply
     * turned h2c off.
     */
    @Test
    void withoutSuchARealmTheConnectionIsStillRegistered() throws Exception {
        CountingSemaphore semaphore = new CountingSemaphore();
        NettyResponseFuture<Object> future = newFuture(semaphore, realm(Realm.AuthScheme.BASIC, "bob"), null);
        future.acquirePartitionLockLazily();

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, requestSender, channelManager, semaphore);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 12345));
            assertNotNull(channel.pipeline().get(ChannelManager.HTTP2_FRAME_CODEC),
                    "sanity: without such a realm the connection really is upgraded, so the assertions above "
                            + "are about the guard rather than about h2c being off");
            assertNotNull(channelManager.pollHttp2Connection(future.getPartitionKey()),
                    "a request-scoped scheme must keep multiplexing");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * An h2 parent is never offered to the HTTP/1.1 pool, so had the guard left it upgraded-but-unregistered
     * the permit would be held until the server dropped the socket.
     */
    @Test
    void closingTheConnectionReturnsThePermit() throws Exception {
        CountingSemaphore semaphore = new CountingSemaphore();
        NettyResponseFuture<Object> future = newFuture(semaphore, realm(Realm.AuthScheme.KERBEROS, null), null);
        future.acquirePartitionLockLazily();

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, requestSender, channelManager, semaphore);
        EmbeddedChannel channel = new EmbeddedChannel();
        listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 12345));
        channel.finishAndReleaseAll();

        assertEquals(semaphore.acquired.get(), semaphore.released.get(),
                "the permit must come back when the socket goes, or maxConnectionsPerHost wedges the host");
    }

    private NettyResponseFuture<Object> newFuture(ConnectionSemaphore semaphore, Realm realm, Realm proxyRealm) {
        Request request = new RequestBuilder().setUrl(H2C_URL).build();
        NettyResponseFuture<Object> future = new NettyResponseFuture<>(request, noopHandler(),
                new NettyRequestFactory(h2cConfig).newNettyRequest(request, false, null, realm, proxyRealm), 0,
                ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, semaphore, null);
        future.setTimeoutsHolder(new TimeoutsHolder(null, future, null, h2cConfig, null));
        future.setRealm(realm);
        future.setProxyRealm(proxyRealm);
        return future;
    }

    private static AsyncHandler<Object> noopHandler() {
        return new AsyncCompletionHandler<Object>() {
            @Override
            public Object onCompleted(Response response) {
                return null;
            }
        };
    }

    private static Realm realm(Realm.AuthScheme scheme, String principal) {
        return new Realm.Builder(principal, "pass").setScheme(scheme).build();
    }

    /**
     * Counts acquire/release rather than reading permits, so a release that never happens and one that
     * happens twice are both visible.
     */
    private static final class CountingSemaphore implements ConnectionSemaphore {

        private final AtomicInteger acquired = new AtomicInteger();
        private final AtomicInteger released = new AtomicInteger();

        @Override
        public void acquireChannelLock(Object partitionKey) throws IOException {
            acquired.incrementAndGet();
        }

        @Override
        public void acquireChannelLock(Object partitionKey, boolean nonBlocking) throws IOException {
            acquired.incrementAndGet();
        }

        @Override
        public void releaseChannelLock(Object partitionKey) {
            released.incrementAndGet();
        }
    }
}
