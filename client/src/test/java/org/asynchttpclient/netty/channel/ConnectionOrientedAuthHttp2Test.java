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

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.netty.ssl.DefaultSslEngineFactory;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.net.ssl.SSLEngine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry poll, the registration and the ALPN advertisement all key off the predicate pinned here.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
class ConnectionOrientedAuthHttp2Test {

    @Test
    void theConnectionOrientedSchemesAreRecognised() {
        for (Realm.AuthScheme scheme : new Realm.AuthScheme[]{
                Realm.AuthScheme.NTLM, Realm.AuthScheme.KERBEROS, Realm.AuthScheme.SPNEGO}) {
            assertTrue(PrincipalScopedPartitionKey.authenticatesTheConnection(realm(scheme, "alice")),
                    scheme + " authenticates the connection, so its connection must not be shared");
        }
    }

    /**
     * An ambient login carries no principal and the ticket still belongs to someone.
     */
    @Test
    void aRealmWithNoPrincipalStillAuthenticatesTheConnection() {
        assertTrue(PrincipalScopedPartitionKey.authenticatesTheConnection(realm(Realm.AuthScheme.KERBEROS, null)));
        assertTrue(PrincipalScopedPartitionKey.authenticatesTheConnection(realm(Realm.AuthScheme.SPNEGO, null)));
    }

    @Test
    void requestScopedSchemesAndNoRealmAreUnaffected() {
        assertFalse(PrincipalScopedPartitionKey.authenticatesTheConnection(null),
                "a request with no realm must keep using the HTTP/2 registry");
        for (Realm.AuthScheme scheme : new Realm.AuthScheme[]{
                Realm.AuthScheme.BASIC, Realm.AuthScheme.DIGEST, Realm.AuthScheme.SCRAM_SHA_256}) {
            assertFalse(PrincipalScopedPartitionKey.authenticatesTheConnection(realm(scheme, "alice")),
                    scheme + " authenticates the request, so multiplexing stays available");
        }
    }

    /**
     * The h2 side ignores the principal; the h1 key cannot.
     */
    @Test
    void theHttp1PoolStillSeparatesPrincipals() {
        Object base = "http://example.com";
        Object alice = PrincipalScopedPartitionKey.scope(base, realm(Realm.AuthScheme.NTLM, "alice"));
        Object bob = PrincipalScopedPartitionKey.scope(base, realm(Realm.AuthScheme.NTLM, "bob"));
        assertNotEquals(alice, bob, "one principal's HTTP/1.1 connection must not be polled for another");
        assertNotEquals(base, alice);
    }

    /**
     * A CONNECT tunnel is one socket, and the proxy realm authenticates the tunnel itself. The gates read
     * both realms, so a tunnel opened under an NTLM proxy is unshareable even when the origin needs no
     * credentials -- the case where the victim request carries no realm of its own.
     */
    @Test
    void anAuthenticatedProxyHopAlsoMakesTheConnectionUnshareable() {
        Realm ntlmProxy = realm(Realm.AuthScheme.NTLM, "alice");
        assertTrue(PrincipalScopedPartitionKey.authenticatesTheConnection(null, ntlmProxy, false),
                "a tunnel the proxy authenticated must not be handed to another principal's request");
        assertTrue(PrincipalScopedPartitionKey.authenticatesTheConnection(
                realm(Realm.AuthScheme.BASIC, "bob"), ntlmProxy, false));
        assertFalse(PrincipalScopedPartitionKey.authenticatesTheConnection(
                realm(Realm.AuthScheme.BASIC, "bob"), realm(Realm.AuthScheme.BASIC, "alice"), false),
                "request-scoped schemes on both hops keep multiplexing available");
        assertFalse(PrincipalScopedPartitionKey.authenticatesTheConnection(null, null, false));
    }

    /**
     * Pin that the flag reaches the engine rather than being dropped by the overload.
     */
    @Test
    void theAlpnDecisionReachesTheSslEngine() {
        List<Boolean> asked = new ArrayList<>();
        AsyncHttpClientConfig config = config().setSslEngineFactory(new DefaultSslEngineFactory() {
            @Override
            public SSLEngine newSslEngine(AsyncHttpClientConfig cfg, String peerHost, int peerPort, boolean http2Allowed) {
                asked.add(http2Allowed);
                return super.newSslEngine(cfg, peerHost, peerPort, http2Allowed);
            }
        }).build();
        Timer timer = new HashedWheelTimer();
        ChannelManager channelManager = new ChannelManager(config, timer);
        EmbeddedChannel refused = new EmbeddedChannel();
        EmbeddedChannel allowed = new EmbeddedChannel();
        try {
            Uri uri = Uri.create("https://example.com:12345/");
            channelManager.addSslHandler(refused.pipeline(), uri, null, false, false);
            channelManager.addSslHandler(allowed.pipeline(), uri, null, false, true);
            assertEquals(Arrays.asList(false, true), asked,
                    "a connection-oriented realm must not advertise h2, and everything else still may");
        } finally {
            refused.finishAndReleaseAll();
            allowed.finishAndReleaseAll();
            channelManager.close();
            timer.stop();
        }
    }

    private static Realm realm(Realm.AuthScheme scheme, String principal) {
        return new Realm.Builder(principal, "pass").setScheme(scheme).build();
    }
}
