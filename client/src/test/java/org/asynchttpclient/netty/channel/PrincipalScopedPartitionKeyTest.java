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

import org.asynchttpclient.Dsl;
import org.asynchttpclient.Realm;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NTLM and Negotiate authenticate the connection rather than the request, so two principals must never
 * share a pooled socket. Schemes that authenticate each request must keep sharing them, or every one of
 * them loses connection reuse.
 */
public class PrincipalScopedPartitionKeyTest {

    private static final Object BASE = "host:443";

    private static Realm realm(Realm.AuthScheme scheme, String principal) {
        return new Realm.Builder(principal, "secret").setScheme(scheme).build();
    }

    @Test
    public void twoPrincipalsOnAConnectionScopedSchemeDoNotShareAKey() {
        for (Realm.AuthScheme scheme : new Realm.AuthScheme[]{
                Realm.AuthScheme.NTLM, Realm.AuthScheme.KERBEROS, Realm.AuthScheme.SPNEGO}) {
            Object alice = PrincipalScopedPartitionKey.scope(BASE, realm(scheme, "alice"));
            Object bob = PrincipalScopedPartitionKey.scope(BASE, realm(scheme, "bob"));

            assertNotEquals(alice, bob, scheme + ": one principal's connection must not be reused by another");
            assertNotEquals(BASE, alice, scheme + ": the scoped key must differ from the unscoped one");
        }
    }

    @Test
    public void theSamePrincipalKeepsTheSameKey() {
        Object first = PrincipalScopedPartitionKey.scope(BASE, realm(Realm.AuthScheme.NTLM, "alice"));
        Object second = PrincipalScopedPartitionKey.scope(BASE, realm(Realm.AuthScheme.NTLM, "alice"));

        assertEquals(first, second, "the same principal must keep reusing its own connections");
        assertEquals(first.hashCode(), second.hashCode());
    }

    /**
     * Basic and Digest send credentials with every request, so their connections are not tied to an
     * identity. Scoping them would cost reuse for no benefit.
     */
    @Test
    public void requestScopedSchemesAreLeftAlone() {
        assertSame(BASE, PrincipalScopedPartitionKey.scope(BASE, realm(Realm.AuthScheme.BASIC, "alice")));
        assertSame(BASE, PrincipalScopedPartitionKey.scope(BASE, realm(Realm.AuthScheme.DIGEST, "alice")));
        assertSame(BASE, PrincipalScopedPartitionKey.scope(BASE, Dsl.basicAuthRealm("alice", "s").build()));
    }

    @Test
    public void noRealmIsLeftAlone() {
        assertSame(BASE, PrincipalScopedPartitionKey.scope(BASE, null));
    }

    /**
     * No principal is still an identity: Kerberos and SPNEGO on the ambient ticket cache set none, and the
     * socket is authenticated all the same.
     */
    @Test
    public void aRealmWithNoPrincipalIsStillScoped() {
        for (Realm.AuthScheme scheme : new Realm.AuthScheme[]{
                Realm.AuthScheme.NTLM, Realm.AuthScheme.KERBEROS, Realm.AuthScheme.SPNEGO}) {
            Realm ambient = new Realm.Builder().setScheme(scheme).build();

            assertTrue(PrincipalScopedPartitionKey.authenticatesTheConnection(ambient),
                    scheme + " authenticates the connection whether or not a principal was configured");
            assertNotEquals(BASE, PrincipalScopedPartitionKey.scope(BASE, ambient),
                    scheme + " with no principal must not share the base key with realm-less traffic");
        }
    }

    /** Two JAAS login contexts are two identities even when neither names a principal. */
    @Test
    public void differentLoginContextsDoNotCollapse() {
        Realm svcA = new Realm.Builder().setScheme(Realm.AuthScheme.KERBEROS).setLoginContextName("svcA").build();
        Realm svcB = new Realm.Builder().setScheme(Realm.AuthScheme.KERBEROS).setLoginContextName("svcB").build();

        assertNotEquals(PrincipalScopedPartitionKey.scope(BASE, svcA),
                PrincipalScopedPartitionKey.scope(BASE, svcB));
    }

    @Test
    public void requestScopedSchemesDoNotAuthenticateTheConnection() {
        assertFalse(PrincipalScopedPartitionKey.authenticatesTheConnection((Realm) null));
        assertFalse(PrincipalScopedPartitionKey.authenticatesTheConnection(
                realm(Realm.AuthScheme.BASIC, "alice")));
        assertFalse(PrincipalScopedPartitionKey.authenticatesTheConnection(
                realm(Realm.AuthScheme.DIGEST, "alice")));
    }

    /** Every field that decides who the socket authenticates as reaches the key: CORP\alice is not OTHER\alice. */
    @Test
    public void everyCredentialFieldSeparatesTheKey() {
        Realm.Builder base = new Realm.Builder("alice", "secret").setScheme(Realm.AuthScheme.NTLM);
        Object reference = PrincipalScopedPartitionKey.scope(BASE, base.build());

        Map<String, Realm> variants = new LinkedHashMap<>();
        variants.put("password", new Realm.Builder("alice", "other").setScheme(Realm.AuthScheme.NTLM).build());
        variants.put("ntlmDomain", new Realm.Builder("alice", "secret").setScheme(Realm.AuthScheme.NTLM).setNtlmDomain("CORP").build());
        variants.put("ntlmHost", new Realm.Builder("alice", "secret").setScheme(Realm.AuthScheme.NTLM).setNtlmHost("hostA").build());
        variants.put("loginContextName", new Realm.Builder("alice", "secret").setScheme(Realm.AuthScheme.NTLM).setLoginContextName("ctxA").build());
        variants.put("servicePrincipalName", new Realm.Builder("alice", "secret").setScheme(Realm.AuthScheme.NTLM).setServicePrincipalName("HTTP/a").build());
        variants.put("customLoginConfig", new Realm.Builder("alice", "secret").setScheme(Realm.AuthScheme.NTLM)
                .setCustomLoginConfig(Collections.singletonMap("keyTab", "/etc/a.keytab")).build());

        for (Map.Entry<String, Realm> variant : variants.entrySet()) {
            assertNotEquals(reference, PrincipalScopedPartitionKey.scope(BASE, variant.getValue()),
                    variant.getKey() + " must change the pool key");
        }
        assertNotEquals(PrincipalScopedPartitionKey.scope(BASE, new Realm.Builder("alice", "secret").setScheme(Realm.AuthScheme.KERBEROS).build()),
                PrincipalScopedPartitionKey.scope(BASE, new Realm.Builder("alice", "secret").setScheme(Realm.AuthScheme.KERBEROS).setRealmName("R1").build()),
                "a Kerberos realm is part of the login");

        // Two values of one field must differ from each other, not merely from the base.
        assertNotEquals(
                PrincipalScopedPartitionKey.scope(BASE, new Realm.Builder("alice", "s").setScheme(Realm.AuthScheme.NTLM).setNtlmDomain("CORP").build()),
                PrincipalScopedPartitionKey.scope(BASE, new Realm.Builder("alice", "s").setScheme(Realm.AuthScheme.NTLM).setNtlmDomain("OTHER").build()),
                "CORP\\\\alice and OTHER\\\\alice are different logins");
    }

    /** A NUL inside a value must not imitate a field separator. */
    @Test
    public void aNulInsideAFieldCannotForgeACollision() {
        Realm nulInPrincipal = new Realm.Builder("a\0", null).setScheme(Realm.AuthScheme.NTLM).build();
        Realm nulInPassword = new Realm.Builder("a", "\0").setScheme(Realm.AuthScheme.NTLM).build();

        assertNotEquals(PrincipalScopedPartitionKey.scope(BASE, nulInPrincipal),
                PrincipalScopedPartitionKey.scope(BASE, nulInPassword));
    }

    /** The proxy realm authenticates the socket to the proxy, so it reaches the key too. */
    @Test
    public void theProxyRealmSeparatesTheKeyToo() {
        Realm proxyA = new Realm.Builder("proxyA", "s").setScheme(Realm.AuthScheme.NTLM).build();
        Realm proxyB = new Realm.Builder("proxyB", "s").setScheme(Realm.AuthScheme.NTLM).build();
        Realm origin = realm(Realm.AuthScheme.BASIC, "alice");

        assertNotEquals(PrincipalScopedPartitionKey.scope(BASE, origin, proxyA, false),
                PrincipalScopedPartitionKey.scope(BASE, origin, proxyB, false),
                "two proxy identities must not share a tunnel");
        assertNotEquals(BASE, PrincipalScopedPartitionKey.scope(BASE, origin, proxyA, false),
                "a proxy realm alone must still scope the key");
        assertEquals(PrincipalScopedPartitionKey.scope(BASE, origin, proxyA, false),
                PrincipalScopedPartitionKey.scope(BASE, origin, proxyA, false),
                "the same pair must keep reusing its own connections");
        assertSame(BASE, PrincipalScopedPartitionKey.scope(BASE, origin, realm(Realm.AuthScheme.BASIC, "bob"), false),
                "request-scoped schemes on both sides stay unscoped");
    }

    /** {@code useCanonicalHostname} changes which service principal the token is minted for. */
    @Test
    public void useCanonicalHostnameSeparatesTheKey() {
        Realm canonical = new Realm.Builder("alice", "s").setScheme(Realm.AuthScheme.KERBEROS)
                .setUseCanonicalHostname(true).build();
        Realm literal = new Realm.Builder("alice", "s").setScheme(Realm.AuthScheme.KERBEROS)
                .setUseCanonicalHostname(false).build();

        assertNotEquals(PrincipalScopedPartitionKey.scope(BASE, canonical),
                PrincipalScopedPartitionKey.scope(BASE, literal));
    }

    /** An absent field and an empty one are different configurations and must not digest alike. */
    @Test
    public void anAbsentFieldDiffersFromAnEmptyOne() {
        Realm absent = new Realm.Builder("alice", null).setScheme(Realm.AuthScheme.NTLM).build();
        Realm empty = new Realm.Builder("alice", "").setScheme(Realm.AuthScheme.NTLM).build();

        assertNotEquals(PrincipalScopedPartitionKey.scope(BASE, absent),
                PrincipalScopedPartitionKey.scope(BASE, empty));
    }

    /**
     * Each realm's scheme is part of its identity: the key's own scheme field is the origin's, so otherwise an
     * NTLM proxy login and a Negotiate one would share a socket.
     */
    @Test
    public void theProxySchemeIsPartOfTheIdentity() {
        Realm scopedOrigin = realm(Realm.AuthScheme.NTLM, "alice");
        Realm proxyNtlm = realm(Realm.AuthScheme.NTLM, "bob");
        Realm proxySpnego = realm(Realm.AuthScheme.SPNEGO, "bob");

        assertNotEquals(PrincipalScopedPartitionKey.scope(BASE, scopedOrigin, proxyNtlm, false),
                PrincipalScopedPartitionKey.scope(BASE, scopedOrigin, proxySpnego, false),
                "an NTLM proxy login and a Negotiate one are not the same identity");
        // Also with no origin realm, where the key's scheme field would have told them apart.
        assertNotEquals(PrincipalScopedPartitionKey.scope(BASE, null, proxyNtlm, false),
                PrincipalScopedPartitionKey.scope(BASE, null, proxySpnego, false));
    }

    /**
     * The two-realm predicate directly. Tested only through {@code scope}, its proxy half could be deleted
     * with the suite still green, and it alone gates the HTTP/2 registry for a proxy-authenticated connection.
     */
    @Test
    public void theTwoRealmPredicateAsksAboutBothRealms() {
        Realm connectionScoped = realm(Realm.AuthScheme.NTLM, "alice");
        Realm requestScoped = realm(Realm.AuthScheme.BASIC, "alice");

        assertTrue(PrincipalScopedPartitionKey.authenticatesTheConnection(null, connectionScoped, false),
                "a connection-authenticating PROXY realm alone must keep the connection out of the registry");
        assertTrue(PrincipalScopedPartitionKey.authenticatesTheConnection(requestScoped, connectionScoped, false),
                "a request-scoped origin realm must not mask a connection-authenticating proxy realm");
        assertTrue(PrincipalScopedPartitionKey.authenticatesTheConnection(connectionScoped, null, false),
                "and the origin half must still answer on its own");
        assertFalse(PrincipalScopedPartitionKey.authenticatesTheConnection(null, null, false));
        assertFalse(PrincipalScopedPartitionKey.authenticatesTheConnection(requestScoped, requestScoped, false),
                "two request-scoped realms leave the connection shareable");
    }

    /** The key reaches the debug log, and its identity digest covers the password, so it is not printed. */
    @Test
    public void theLoggedKeyCarriesNoIdentity() {
        String logged = PrincipalScopedPartitionKey.scope(BASE, realm(Realm.AuthScheme.NTLM, "alice")).toString();
        assertFalse(logged.contains("alice"), logged);
        assertFalse(logged.matches(".*[0-9a-f]{64}.*"), logged);
    }

    /** A SOCKS login and a CONNECT authenticate the connection; plain HTTP through a proxy does not. */
    @Test
    public void whichProxyHopsAuthenticateTheConnection() {
        ProxyServer httpProxy = new ProxyServer.Builder("proxy", 8080).build();
        ProxyServer socks = new ProxyServer.Builder("proxy", 1080).setProxyType(ProxyType.SOCKS_V5).build();
        assertFalse(PrincipalScopedPartitionKey.proxyHopIsPerConnection(null, Uri.create("https://example.com/")));
        assertFalse(PrincipalScopedPartitionKey.proxyHopIsPerConnection(httpProxy, Uri.create("http://example.com/")));
        assertTrue(PrincipalScopedPartitionKey.proxyHopIsPerConnection(httpProxy, Uri.create("https://example.com/")));
        assertTrue(PrincipalScopedPartitionKey.proxyHopIsPerConnection(httpProxy, Uri.create("ws://example.com/")));
        assertTrue(PrincipalScopedPartitionKey.proxyHopIsPerConnection(socks, Uri.create("http://example.com/")));
    }

    @Test
    public void onAPerConnectionProxyHopAnyLoginScopesTheKey() {
        Realm alice = realm(Realm.AuthScheme.BASIC, "alice");
        assertNotEquals(PrincipalScopedPartitionKey.scope(BASE, null, alice, true),
                PrincipalScopedPartitionKey.scope(BASE, null, realm(Realm.AuthScheme.BASIC, "bob"), true));
        assertSame(BASE, PrincipalScopedPartitionKey.scope(BASE, null, alice, false));
        assertSame(BASE, PrincipalScopedPartitionKey.scope(BASE, null, null, true), "an anonymous proxy hop is nobody");
        assertTrue(PrincipalScopedPartitionKey.authenticatesTheConnection(null, alice, true));
        assertFalse(PrincipalScopedPartitionKey.authenticatesTheConnection(null, alice, false));
    }

    /** The realm a Digest 407 names is not part of the login, or the tunnel is filed where no request polls. */
    @Test
    public void aDigestChallengeRealmDoesNotMoveTheKey() {
        Realm configured = realm(Realm.AuthScheme.DIGEST, "alice");
        Realm challenged = new Realm.Builder("alice", "secret").setScheme(Realm.AuthScheme.DIGEST)
                .parseProxyAuthenticateHeader("Digest realm=\"proxy\", nonce=\"n\"").build();
        assertEquals(PrincipalScopedPartitionKey.scope(BASE, null, configured, true),
                PrincipalScopedPartitionKey.scope(BASE, null, challenged, true));
    }

    /** A SOCKS handshake sends the proxy's own login whatever the future holds, so that is the one keyed. */
    @Test
    public void aSocksHopIsKeyedByTheProxysOwnLogin() {
        Realm alice = realm(Realm.AuthScheme.BASIC, "alice");
        ProxyServer socks = new ProxyServer.Builder("proxy", 1080).setProxyType(ProxyType.SOCKS_V5).setRealm(alice).build();
        ProxyServer http = new ProxyServer.Builder("proxy", 3128).setRealm(alice).build();
        assertSame(alice, PrincipalScopedPartitionKey.proxyLogin(null, socks));
        assertNull(PrincipalScopedPartitionKey.proxyLogin(null, http));
        assertNull(PrincipalScopedPartitionKey.proxyLogin(null, null));
    }

    /**
     * Different hosts must stay separate even for one principal, or the scoping would collapse the
     * distinction the base key exists to make.
     */
    @Test
    public void theBaseKeyStillSeparatesHosts() {
        Realm alice = realm(Realm.AuthScheme.NTLM, "alice");

        assertNotEquals(PrincipalScopedPartitionKey.scope("host-a:443", alice),
                PrincipalScopedPartitionKey.scope("host-b:443", alice));
    }
}
