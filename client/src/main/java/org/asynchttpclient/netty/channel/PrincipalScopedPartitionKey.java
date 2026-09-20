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

import org.asynchttpclient.Realm;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Channel-pool partition key for schemes that authenticate the connection rather than the request.
 *
 * <p>NTLM and Negotiate complete a handshake once and the server then treats every later request arriving
 * on that socket as coming from the identity that authenticated it. The regular partition key describes
 * only where the connection goes, so a socket one principal authenticated could be handed to a request
 * belonging to another, and the server would serve it as the first principal. Nothing on the wire shows the
 * identity changed, because the second request carries no authentication headers of its own.
 *
 * <p>Folding the principal into the key keeps those connections separated. Basic and Digest need no such
 * thing: they authenticate each request and their credentials travel with it.
 *
 * <p>If a site that offers a connection to the pool and a site that polls for one ever disagree about
 * whether to scope, the poll simply misses and a new connection is opened. That costs reuse, never
 * correctness, which is the right way round for this to fail.
 */
public final class PrincipalScopedPartitionKey {

    private final Object baseKey;
    private final Realm.AuthScheme scheme;
    private final String principal;

    private PrincipalScopedPartitionKey(Object baseKey, Realm.AuthScheme scheme, String principal) {
        this.baseKey = baseKey;
        this.scheme = scheme;
        this.principal = principal;
    }

    /**
     * Wraps {@code baseKey} with the authenticated identity when {@code realm} uses a scheme that
     * authenticates the connection, and returns {@code baseKey} unchanged otherwise. Every site that
     * derives a pool key must apply this the same way.
     */
    public static Object scope(Object baseKey, Realm realm) {
        if (realm == null || realm.getPrincipal() == null || !authenticatesTheConnection(realm.getScheme())) {
            return baseKey;
        }
        return new PrincipalScopedPartitionKey(baseKey, realm.getScheme(), realm.getPrincipal());
    }

    /**
     * Whether the scheme authenticates the connection rather than the request. Unlike
     * {@link #scope(Object, Realm)} this needs no principal: an ambient Kerberos login carries none and still
     * binds the socket.
     */
    public static boolean authenticatesTheConnection(@Nullable Realm realm) {
        return realm != null && authenticatesTheConnection(realm.getScheme());
    }

    /**
     * Either hop, since a CONNECT tunnel is one socket.
     */
    public static boolean authenticatesTheConnection(@Nullable Realm realm, @Nullable Realm proxyRealm) {
        return authenticatesTheConnection(realm) || authenticatesTheConnection(proxyRealm);
    }

    private static boolean authenticatesTheConnection(Realm.AuthScheme scheme) {
        return scheme == Realm.AuthScheme.NTLM
                || scheme == Realm.AuthScheme.KERBEROS
                || scheme == Realm.AuthScheme.SPNEGO;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PrincipalScopedPartitionKey that = (PrincipalScopedPartitionKey) o;
        return Objects.equals(baseKey, that.baseKey)
                && scheme == that.scheme
                && Objects.equals(principal, that.principal);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Objects.hashCode(baseKey) + Objects.hashCode(scheme)) + Objects.hashCode(principal);
    }

    @Override
    public String toString() {
        // The principal is a username, not a secret, and the existing keys print their host and proxy.
        return "PrincipalScopedPartitionKey(baseKey=" + baseKey + ", scheme=" + scheme + ", principal=" + principal + ')';
    }
}
