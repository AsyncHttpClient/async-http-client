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

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.Map;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the base-key-grouped HTTP/2 registry and the sibling-connection lookup that backs the
 * round-robin permit-starved reuse path (issue #2214). These exercise
 * {@link ChannelManager#pollHttp2SiblingConnection(Object)}, the exact-key {@link ChannelManager#pollHttp2Connection(Object)}
 * over the nested map, and the empty-inner-map pruning, without any network I/O.
 */
class ChannelManagerHttp2SiblingLookupTest {

    private static final String BASE = "https://host:443";

    private ChannelManager channelManager;
    private Timer timer;

    @BeforeEach
    void setUp() {
        AsyncHttpClientConfig cfg = config().build();
        timer = new HashedWheelTimer();
        channelManager = new ChannelManager(cfg, timer);
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

    private static RoundRobinPartitionKey rrKey(String base, String ip) throws Exception {
        return new RoundRobinPartitionKey(base, InetAddress.getByName(ip));
    }

    /** Registers an active EmbeddedChannel under the given key, attaching a (optionally draining) H2 state. */
    private EmbeddedChannel register(Object partitionKey, boolean draining) {
        EmbeddedChannel channel = new EmbeddedChannel();
        Http2ConnectionState state = new Http2ConnectionState();
        if (draining) {
            state.setDraining(0);
        }
        channel.attr(Http2ConnectionState.HTTP2_STATE_KEY).set(state);
        channelManager.registerHttp2Connection(partitionKey, channel);
        return channel;
    }

    @Test
    void siblingLookupReturnsConnectionForSameBaseDifferentIp() throws Exception {
        EmbeddedChannel chA = register(rrKey(BASE, "127.0.0.1"), false);

        // A request pinned to a different IP misses the exact key but must find the sibling on IP_A.
        assertNull(channelManager.pollHttp2Connection(rrKey(BASE, "127.0.0.2")), "exact per-IP poll must miss");
        assertSame(chA, channelManager.pollHttp2SiblingConnection(BASE), "sibling lookup must find the IP_A connection");
    }

    @Test
    void exactPollStillResolvesOverNestedMap() throws Exception {
        EmbeddedChannel chA = register(rrKey(BASE, "127.0.0.1"), false);
        assertSame(chA, channelManager.pollHttp2Connection(rrKey(BASE, "127.0.0.1")), "exact per-IP poll must hit");
    }

    @Test
    void defaultModePlainKeyStillResolves() {
        // DEFAULT mode registers under the plain base key; baseKeyOf returns it unchanged, so the inner
        // map holds a single entry and the exact poll behaves exactly as the old flat map did.
        EmbeddedChannel ch = register(BASE, false);
        assertSame(ch, channelManager.pollHttp2Connection(BASE), "plain-key exact poll must hit");
    }

    @Test
    void siblingLookupSkipsDrainingConnection() throws Exception {
        register(rrKey(BASE, "127.0.0.1"), true);
        assertNull(channelManager.pollHttp2SiblingConnection(BASE), "a draining sibling must not be handed out");
    }

    @Test
    void siblingLookupReturnsHealthyAmongDraining() throws Exception {
        register(rrKey(BASE, "127.0.0.1"), true);
        EmbeddedChannel healthy = register(rrKey(BASE, "127.0.0.2"), false);
        assertSame(healthy, channelManager.pollHttp2SiblingConnection(BASE),
                "lookup must skip the draining sibling and return the healthy one");
    }

    @Test
    void siblingLookupSkipsClosedConnection() throws Exception {
        EmbeddedChannel chA = register(rrKey(BASE, "127.0.0.1"), false);
        chA.close().sync();
        assertNull(channelManager.pollHttp2SiblingConnection(BASE), "a closed sibling must not be handed out");
    }

    @Test
    void siblingLookupIgnoresDifferentBase() throws Exception {
        register(rrKey(BASE, "127.0.0.1"), false);
        assertNull(channelManager.pollHttp2SiblingConnection("https://other:443"),
                "lookup must not cross base-key (host) boundaries");
    }

    @Test
    void siblingLookupReturnsNullForUnknownBase() {
        assertNull(channelManager.pollHttp2SiblingConnection(BASE), "no connections registered yet");
    }

    @Test
    void emptyInnerMapIsPrunedOnRemoval() throws Exception {
        RoundRobinPartitionKey keyA = rrKey(BASE, "127.0.0.1");
        RoundRobinPartitionKey keyB = rrKey(BASE, "127.0.0.2");
        EmbeddedChannel chA = register(keyA, false);
        EmbeddedChannel chB = register(keyB, false);
        assertTrue(outerMapContainsBase(BASE), "base entry present after registering two siblings");

        // Removing one sibling keeps the inner map (still has the other).
        channelManager.removeHttp2Connection(keyA, chA);
        assertTrue(outerMapContainsBase(BASE), "base entry kept while a sibling remains");
        assertSame(chB, channelManager.pollHttp2SiblingConnection(BASE), "remaining sibling still found");

        // Removing the last sibling prunes the empty inner map from the outer registry.
        channelManager.removeHttp2Connection(keyB, chB);
        assertFalse(outerMapContainsBase(BASE), "emptied inner map must be pruned from the outer registry");
        assertNull(channelManager.pollHttp2SiblingConnection(BASE), "no sibling after the last is removed");
    }

    @SuppressWarnings("unchecked")
    private boolean outerMapContainsBase(Object baseKey) throws Exception {
        Field f = ChannelManager.class.getDeclaredField("http2Connections");
        f.setAccessible(true);
        return ((Map<Object, ?>) f.get(channelManager)).containsKey(baseKey);
    }
}
