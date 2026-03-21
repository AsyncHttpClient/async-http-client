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
package org.asynchttpclient.scram;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe cache for SCRAM reauthentication data (RFC 7804 §5.1).
 * Keyed by (host, port, realm). Stores derived keys (NOT SaltedPassword).
 */
public class ScramSessionCache {

    private final ConcurrentHashMap<CacheKey, Entry> cache = new ConcurrentHashMap<>();
    private final int maxEntries;

    public ScramSessionCache() {
        this(1000);
    }

    public ScramSessionCache(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * Cache key: (host, port, realm).
     */
    public static class CacheKey {
        private final String host;
        private final int port;
        private final @Nullable String realm;

        public CacheKey(String host, int port, @Nullable String realm) {
            this.host = host;
            this.port = port;
            this.realm = realm;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey)) return false;
            CacheKey that = (CacheKey) o;
            return port == that.port && host.equals(that.host) && Objects.equals(realm, that.realm);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, port, realm);
        }
    }

    /**
     * Cached session entry for reauthentication.
     * SECURITY: No SaltedPassword stored — only derived keys.
     */
    public static class Entry {
        public final @Nullable String realmName;
        public final byte[] salt;
        public final int iterationCount;
        public final byte[] clientKey;
        public final byte[] storedKey;
        public final byte[] serverKey;
        public volatile @Nullable String sr;
        public volatile int ttl;         // -1 = no expiration
        public volatile long srTimestampNanos;
        public final AtomicInteger nonceCount;
        public final String originalServerFirstMessage;

        public Entry(@Nullable String realmName, byte[] salt, int iterationCount,
                     byte[] clientKey, byte[] storedKey, byte[] serverKey,
                     @Nullable String sr, int ttl, long srTimestampNanos,
                     int initialNonceCount, String originalServerFirstMessage) {
            this.realmName = realmName;
            this.salt = salt;
            this.iterationCount = iterationCount;
            this.clientKey = clientKey;
            this.storedKey = storedKey;
            this.serverKey = serverKey;
            this.sr = sr;
            this.ttl = ttl;
            this.srTimestampNanos = srTimestampNanos;
            this.nonceCount = new AtomicInteger(initialNonceCount);
            this.originalServerFirstMessage = originalServerFirstMessage;
        }
    }

    public void put(CacheKey key, Entry entry) {
        // Size-bounded eviction: remove oldest entry by sr timestamp
        if (cache.size() >= maxEntries) {
            CacheKey oldest = null;
            long oldestTimestamp = Long.MAX_VALUE;
            for (Map.Entry<CacheKey, Entry> e : cache.entrySet()) {
                if (e.getValue().srTimestampNanos < oldestTimestamp) {
                    oldestTimestamp = e.getValue().srTimestampNanos;
                    oldest = e.getKey();
                }
            }
            if (oldest != null) {
                cache.remove(oldest);
            }
        }
        cache.put(key, entry);
    }

    public @Nullable Entry get(CacheKey key) {
        return cache.get(key);
    }

    /**
     * Update the server nonce (sr) after a stale response.
     */
    public void updateSr(CacheKey key, String newSr, int newTtl) {
        Entry entry = cache.get(key);
        if (entry != null) {
            entry.sr = newSr;
            entry.ttl = newTtl;
            entry.srTimestampNanos = System.nanoTime();
        }
    }

    /**
     * Check if the sr value is still fresh.
     * If ttl == -1: always fresh (no expiration).
     * If ttl &gt;= 0: check elapsed time since sr was received.
     */
    public boolean isSrFresh(Entry entry) {
        if (entry.sr == null) {
            return false;
        }
        if (entry.ttl < 0) {
            return true; // No expiration — rely on server stale=true
        }
        long elapsedNanos = System.nanoTime() - entry.srTimestampNanos;
        long ttlNanos = (long) entry.ttl * 1_000_000_000L;
        return elapsedNanos < ttlNanos;
    }

    /**
     * Atomically reserve and increment the nonce-count for use in a request.
     * Call rollbackNonceCount on failure to undo.
     */
    public int reserveNonceCount(CacheKey key) {
        Entry entry = cache.get(key);
        if (entry == null) {
            return -1;
        }
        return entry.nonceCount.getAndIncrement();
    }

    /**
     * Confirm nonce-count after successful reauthentication. No-op since already incremented.
     */
    public void confirmNonceCount(CacheKey key) {
        // No-op: nonce-count was already atomically incremented in reserveNonceCount
    }

    /**
     * Rollback nonce-count on failure or stale response by decrementing.
     */
    public void rollbackNonceCount(CacheKey key) {
        Entry entry = cache.get(key);
        if (entry != null) {
            entry.nonceCount.decrementAndGet();
        }
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }
}
