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

import io.netty.buffer.ByteBufUtil;
import org.asynchttpclient.Realm;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Channel-pool partition key for schemes that authenticate the connection rather than the request.
 *
 * <p>NTLM and Negotiate complete a handshake once and the server then treats every later request arriving
 * on that socket as coming from the identity that authenticated it. The regular partition key describes
 * only where the connection goes, so a socket one principal authenticated could be handed to a request
 * belonging to another, and the server would serve it as the first principal. Nothing on the wire shows the
 * identity changed, because the second request carries no authentication headers of its own.
 *
 * <p>Folding that identity into the key keeps those connections separated. Basic and Digest need no such
 * thing: they authenticate each request and their credentials travel with it.
 *
 * <p>If a site that offers a connection to the pool and a site that polls for one ever disagree about
 * whether to scope, the poll simply misses and a new connection is opened. That costs reuse, never
 * correctness, which is the right way round for this to fail.
 */
public final class PrincipalScopedPartitionKey {

    // Per thread, since scope() runs on the event loop for every poll and offer.
    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java SE implementation is required to provide SHA-256.
            throw new IllegalStateException(e);
        }
    });

    private static final int CACHED_IDENTITIES = 16;

    // Element 2i holds a WeakReference to a realm and 2i + 1 one to its identity. JDK types only: a thread-local
    // value of a class of ours would keep this library's class loader alive for as long as the thread lives.
    private static final ThreadLocal<Object[]> IDENTITIES =
            ThreadLocal.withInitial(() -> new Object[2 * CACHED_IDENTITIES]);

    private final Object baseKey;
    private final Realm.AuthScheme scheme;
    private final @Nullable String originIdentity;
    private final @Nullable String proxyIdentity;

    private PrincipalScopedPartitionKey(Object baseKey, Realm.AuthScheme scheme, @Nullable String originIdentity,
                                        @Nullable String proxyIdentity) {
        this.baseKey = baseKey;
        this.scheme = scheme;
        this.originIdentity = originIdentity;
        this.proxyIdentity = proxyIdentity;
    }

    /**
     * Wraps {@code baseKey} with the authenticated identity when {@code realm} uses a scheme that
     * authenticates the connection, and returns {@code baseKey} unchanged otherwise. Every site that
     * derives a pool key must apply this the same way.
     */
    public static Object scope(Object baseKey, Realm realm) {
        return scope(baseKey, realm, null, false);
    }

    /**
     * The pool key for the connection an exchange ran on.
     */
    public static Object scope(NettyResponseFuture<?> future) {
        return scope(future.getPartitionKey(), future.getRealm(), proxyLogin(future.getProxyRealm(), future.getProxyServer()),
                proxyHopIsPerConnection(future.getProxyServer(), future.getUri()));
    }

    /**
     * The login a proxy hop runs as. A SOCKS handshake always sends the proxy's own realm, even after a
     * cross-origin redirect has cleared the future's.
     */
    public static @Nullable Realm proxyLogin(@Nullable Realm proxyRealm, @Nullable ProxyServer proxy) {
        return proxy != null && proxy.getProxyType().isSocks() ? proxy.getRealm() : proxyRealm;
    }

    /**
     * As {@link #scope(Object, Realm)}, but also keyed by the identity that authenticated the connection to a
     * proxy: a tunnel is one socket, so it belongs to both.
     */
    public static Object scope(Object baseKey, @Nullable Realm realm, @Nullable Realm proxyRealm,
                               boolean proxyHopIsPerConnection) {
        boolean originScoped = authenticatesTheConnection(realm);
        boolean proxyScoped = proxyAuthenticatesTheConnection(proxyRealm, proxyHopIsPerConnection);
        if (!originScoped && !proxyScoped) {
            return baseKey;
        }
        Realm.AuthScheme scheme = originScoped ? realm.getScheme() : proxyRealm.getScheme();
        return new PrincipalScopedPartitionKey(baseKey, scheme, originScoped ? identityOf(realm) : null,
                proxyScoped ? identityOf(proxyRealm) : null);
    }

    /**
     * The digest of {@code realm}, computed once per realm instance and thread. Every input but the login
     * config is a final field of the realm. The config is the caller's own map, which they can still change
     * after build(), so a realm that has one is digested every time.
     */
    static String identityOf(Realm realm) {
        if (realm.getCustomLoginConfig() != null) {
            return digest(realm);
        }
        Object[] cache = IDENTITIES.get();
        int slot = System.identityHashCode(realm) & (CACHED_IDENTITIES - 1);
        // Two ways per set, so an origin and a proxy realm that collide both stay cached.
        int other = slot ^ 1;
        String identity = cached(cache, slot, realm);
        if (identity == null) {
            identity = cached(cache, other, realm);
        }
        if (identity != null) {
            return identity;
        }
        identity = digest(realm);
        cache[2 * other] = cache[2 * slot];
        cache[2 * other + 1] = cache[2 * slot + 1];
        // Both weak: the cache keeps neither the realm, and with it the password, nor the digest alive. A digest
        // no pool key holds is computed again after the next collection.
        cache[2 * slot] = new WeakReference<>(realm);
        cache[2 * slot + 1] = new WeakReference<>(identity);
        return identity;
    }

    // A cleared reference never matches, so a realm allocated after another was collected cannot pick up its
    // identity even if it lands in the same slot.
    private static @Nullable String cached(Object[] cache, int slot, Realm realm) {
        Object realmReference = cache[2 * slot];
        if (realmReference == null || ((WeakReference<?>) realmReference).get() != realm) {
            return null;
        }
        return (String) ((WeakReference<?>) cache[2 * slot + 1]).get();
    }

    /**
     * A digest of every field that decides who the connection ends up authenticated as, not just the
     * principal: {@code CORP\alice} is not {@code OTHER\alice}. Leaving one out shares a socket between two
     * identities, while an extra one only costs a pool miss. Hashed so the key does not hold the password.
     */
    static String digest(Realm realm) {
        MessageDigest digest = DIGEST.get();
        digest.reset();
        // The key's own scheme field is the origin's, so without this an NTLM proxy login and a Negotiate
        // one would share a socket.
        update(digest, realm.getScheme().name());
        update(digest, realm.getPrincipal());
        update(digest, realm.getPassword());
        update(digest, realm.getNtlmDomain());
        update(digest, realm.getNtlmHost());
        update(digest, realm.getLoginContextName());
        update(digest, realm.getServicePrincipalName());
        // Part of a Kerberos login. For Digest the 407 challenge fills it in, so keying on it files the tunnel
        // where the next request, still holding the configured realm, never looks.
        Realm.AuthScheme scheme = realm.getScheme();
        update(digest, scheme == Realm.AuthScheme.KERBEROS || scheme == Realm.AuthScheme.SPNEGO ? realm.getRealmName() : null);
        // Changes the service principal the token is minted for.
        digest.update((byte) (realm.isUseCanonicalHostname() ? 1 : 0));
        Map<String, String> loginConfig = realm.getCustomLoginConfig();
        if (loginConfig != null) {
            // Sorted, so equal configurations built in a different order still agree.
            for (Map.Entry<String, String> entry : new TreeMap<>(loginConfig).entrySet()) {
                update(digest, entry.getKey());
                update(digest, entry.getValue());
            }
        }
        return ByteBufUtil.hexDump(digest.digest());
    }

    // Length-prefixed rather than terminated: a string can hold U+0000, so a terminator could be forged.
    private static void update(MessageDigest digest, @Nullable String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 1);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    /**
     * Whether the scheme authenticates the connection rather than the request. It needs no principal: an
     * ambient Kerberos login carries none and still binds the socket.
     */
    public static boolean authenticatesTheConnection(@Nullable Realm realm) {
        return realm != null && authenticatesTheConnection(realm.getScheme());
    }

    public static boolean anyHopAuthenticatesTheConnection(NettyResponseFuture<?> future) {
        return authenticatesTheConnection(future.getRealm(), proxyLogin(future.getProxyRealm(), future.getProxyServer()),
                proxyHopIsPerConnection(future.getProxyServer(), future.getUri()));
    }

    /**
     * Either hop, since a CONNECT tunnel is one socket.
     */
    public static boolean authenticatesTheConnection(@Nullable Realm realm, @Nullable Realm proxyRealm,
                                                     boolean proxyHopIsPerConnection) {
        return authenticatesTheConnection(realm) || proxyAuthenticatesTheConnection(proxyRealm, proxyHopIsPerConnection);
    }

    /**
     * Whether the proxy authenticates the connection itself, whatever the scheme: a SOCKS login does, and so
     * does the CONNECT that opens a tunnel, since the proxy sees no request after it. Plain HTTP through an
     * HTTP proxy sends its credentials with every request instead.
     */
    public static boolean proxyHopIsPerConnection(@Nullable ProxyServer proxy, Uri uri) {
        return proxy != null && (proxy.getProxyType().isSocks() || uri.isSecured() || uri.isWebSocket());
    }

    private static boolean proxyAuthenticatesTheConnection(@Nullable Realm proxyRealm, boolean proxyHopIsPerConnection) {
        return proxyRealm != null && (proxyHopIsPerConnection || authenticatesTheConnection(proxyRealm));
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
                && Objects.equals(originIdentity, that.originIdentity)
                && Objects.equals(proxyIdentity, that.proxyIdentity);
    }

    @Override
    public int hashCode() {
        int result = 31 * Objects.hashCode(baseKey) + Objects.hashCode(scheme);
        result = 31 * result + Objects.hashCode(originIdentity);
        return 31 * result + Objects.hashCode(proxyIdentity);
    }

    @Override
    public String toString() {
        // No identity: it digests the password too, and an unsalted digest in a debug log can be guessed offline.
        return "PrincipalScopedPartitionKey(baseKey=" + baseKey + ", scheme=" + scheme + ')';
    }
}
