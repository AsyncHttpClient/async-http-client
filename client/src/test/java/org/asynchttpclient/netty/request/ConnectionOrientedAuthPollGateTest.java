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

import io.netty.channel.Channel;
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
import org.asynchttpclient.proxy.ProxyServer;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Method;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The registration skip does nothing about the other direction: a realm-less request registers a connection,
 * an NTLM request draws it and authenticates a socket anyone can reach, and the next realm-less request rides
 * it as that principal. Only the poll gate stops that.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
class ConnectionOrientedAuthPollGateTest {

    private static final String URL = "https://example.com:443/";

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

    @Test
    void aConnectionOrientedRealmDoesNotDrawARegisteredHttp2Connection() throws Exception {
        EmbeddedChannel registered = new EmbeddedChannel();
        try {
            Request request = new RequestBuilder().setUrl(URL).build();
            Object key = request.getChannelPoolPartitioning()
                    .getPartitionKey(request.getUri(), request.getVirtualHost(), null);
            channelManager.registerHttp2Connection(key, registered);

            assertNull(poll(realm(Realm.AuthScheme.NTLM, "alice")),
                    "authenticating a connection anyone can reach hands every later request that identity");
            assertNull(poll(realm(Realm.AuthScheme.KERBEROS, null)),
                    "an ambient Kerberos login binds the socket just the same");
            assertNotNull(poll(realm(Realm.AuthScheme.BASIC, "bob")),
                    "sanity: a request-scoped scheme still multiplexes, so this pins the gate");
        } finally {
            registered.finishAndReleaseAll();
        }
    }

    @Test
    void aConnectionOrientedProxyRealmDoesNotDrawOneEither() throws Exception {
        EmbeddedChannel registered = new EmbeddedChannel();
        try {
            Request request = new RequestBuilder().setUrl(URL).build();
            Object key = request.getChannelPoolPartitioning()
                    .getPartitionKey(request.getUri(), request.getVirtualHost(), null);
            channelManager.registerHttp2Connection(key, registered);

            NettyResponseFuture<Object> future = newFuture(realm(Realm.AuthScheme.BASIC, "bob"),
                    realm(Realm.AuthScheme.NTLM, "alice"));
            assertNull(pollWith(future), "a request whose proxy realm authenticates the connection must not "
                    + "multiplex onto a shared one either");
        } finally {
            registered.finishAndReleaseAll();
        }
    }

    /**
     * ROUND_ROBIN pins reuse to one IP through a key override, which is a separate branch with its own copy
     * of the gate.
     */
    @Test
    void theRoundRobinOverridePathIsGatedToo() throws Exception {
        EmbeddedChannel registered = new EmbeddedChannel();
        try {
            Object override = "round-robin-override-key";
            channelManager.registerHttp2Connection(override, registered);

            NettyResponseFuture<Object> ntlm = newFuture(realm(Realm.AuthScheme.NTLM, "alice"), null);
            ntlm.setPartitionKeyOverride(override);
            assertNull(pollWith(ntlm), "the override branch must apply the same gate as the base-key branch");

            NettyResponseFuture<Object> basic = newFuture(realm(Realm.AuthScheme.BASIC, "bob"), null);
            basic.setPartitionKeyOverride(override);
            assertNotNull(pollWith(basic), "sanity: the override branch still multiplexes otherwise");
        } finally {
            registered.finishAndReleaseAll();
        }
    }

    private @Nullable Channel poll(Realm realm) throws Exception {
        return pollWith(newFuture(realm, null));
    }

    private @Nullable Channel pollWith(NettyResponseFuture<Object> future) throws Exception {
        Method pollPooledChannel = NettyRequestSender.class.getDeclaredMethod("pollPooledChannel",
                NettyResponseFuture.class, Request.class, ProxyServer.class, AsyncHandler.class);
        pollPooledChannel.setAccessible(true);
        return (Channel) pollPooledChannel.invoke(sender, future, future.getCurrentRequest(), null, noopHandler());
    }

    private NettyResponseFuture<Object> newFuture(Realm realm, Realm proxyRealm) {
        Request request = new RequestBuilder().setUrl(URL).build();
        NettyResponseFuture<Object> future = new NettyResponseFuture<>(request, noopHandler(), null, 0,
                ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
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
}
