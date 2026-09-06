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
import org.asynchttpclient.netty.channel.PrincipalScopedPartitionKey;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Method;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A SOCKS login and the CONNECT that opens a tunnel authenticate the connection, whatever the scheme, so the
 * connection belongs to that proxy identity. The offer key is built the way the offer sites build it and the
 * poll goes through the sender with no future yet, as on a request's first attempt, so the two have to agree.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
class ProxyHopIdentityPoolTest {

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
        channelManager.close();
        timer.stop();
    }

    @Test
    void aSocksLoginKeepsItsConnectionToItself() throws Exception {
        assertOnlyTheSameProxyIdentityReuses("http://example.com/", ProxyType.SOCKS_V5);
    }

    @Test
    void aTunnelOpenedWithBasicProxyCredentialsKeepsItsConnectionToItself() throws Exception {
        assertOnlyTheSameProxyIdentityReuses("https://example.com/", ProxyType.HTTP);
    }

    @Test
    void plainHttpThroughAProxyStillSharesItsConnection() throws Exception {
        EmbeddedChannel channel = offeredBy("http://example.com/", proxy(ProxyType.HTTP, "alice"));
        try {
            assertSame(channel, poll("http://example.com/", proxy(ProxyType.HTTP, "bob")),
                    "absolute-form requests carry their own credentials, so the connection is anyone's");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void anAuthenticatedProxyHopDoesNotDrawARegisteredHttp2Connection() throws Exception {
        String url = "https://example.com/";
        ProxyServer alice = proxy(ProxyType.HTTP, "alice");
        Request request = request(url, alice);
        EmbeddedChannel registered = new EmbeddedChannel();
        try {
            channelManager.registerHttp2Connection(request.getChannelPoolPartitioning()
                    .getPartitionKey(request.getUri(), null, alice), registered);
            assertNull(poll(url, alice), "a multiplexed connection is shared, so it cannot carry a proxy login");
        } finally {
            registered.finishAndReleaseAll();
        }
    }

    private void assertOnlyTheSameProxyIdentityReuses(String url, ProxyType type) throws Exception {
        EmbeddedChannel channel = offeredBy(url, proxy(type, "alice"));
        try {
            assertNull(poll(url, proxy(type, "bob")), "bob's request must not ride alice's authenticated connection");
            assertSame(channel, poll(url, proxy(type, "alice")), "alice still reuses her own");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel offeredBy(String url, ProxyServer proxy) {
        NettyResponseFuture<Object> future = new NettyResponseFuture<>(request(url, proxy), noopHandler(), null, 0,
                ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, proxy);
        future.setProxyRealm(proxy.getRealm());
        EmbeddedChannel channel = new EmbeddedChannel();
        channelManager.tryToOfferChannelToPool(channel, noopHandler(), true, PrincipalScopedPartitionKey.scope(future));
        return channel;
    }

    private @Nullable Channel poll(String url, ProxyServer proxy) throws Exception {
        Method pollPooledChannel = NettyRequestSender.class.getDeclaredMethod("pollPooledChannel",
                NettyResponseFuture.class, Request.class, ProxyServer.class, AsyncHandler.class);
        pollPooledChannel.setAccessible(true);
        return (Channel) pollPooledChannel.invoke(sender, null, request(url, proxy), proxy, noopHandler());
    }

    private static Request request(String url, ProxyServer proxy) {
        return new RequestBuilder().setUrl(url).setProxyServer(proxy).build();
    }

    private static ProxyServer proxy(ProxyType type, String user) {
        return new ProxyServer.Builder("proxy.example", 1080)
                .setProxyType(type)
                .setRealm(new Realm.Builder(user, "secret").setScheme(Realm.AuthScheme.BASIC).build())
                .build();
    }

    private static AsyncHandler<Object> noopHandler() {
        return new AsyncCompletionHandler<Object>() {
            @Override
            public Object onCompleted(Response response) {
                return null;
            }
        };
    }
}
