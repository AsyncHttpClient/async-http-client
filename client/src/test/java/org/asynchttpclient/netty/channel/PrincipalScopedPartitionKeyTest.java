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
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertSame;

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
        assertEquals(second.hashCode(), first.hashCode());
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
    public void noRealmAndNoPrincipalAreLeftAlone() {
        assertSame(BASE, PrincipalScopedPartitionKey.scope(BASE, null));
        assertSame(BASE, PrincipalScopedPartitionKey.scope(BASE,
                new Realm.Builder(null, null).setScheme(Realm.AuthScheme.NTLM).build()));
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
