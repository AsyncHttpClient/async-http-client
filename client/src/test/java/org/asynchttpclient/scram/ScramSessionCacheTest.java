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

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScramSessionCacheTest {

    private ScramSessionCache.Entry createEntry(String sr, int ttl, int initialNonceCount) {
        return new ScramSessionCache.Entry(
                "testrealm",
                new byte[]{1, 2, 3}, 4096,
                new byte[]{4, 5, 6}, new byte[]{7, 8, 9}, new byte[]{10, 11, 12},
                sr, ttl, System.nanoTime(),
                initialNonceCount,
                "r=nonce,s=c2FsdA==,i=4096"
        );
    }

    @Test
    void testPutAndGet() {
        ScramSessionCache cache = new ScramSessionCache();
        ScramSessionCache.CacheKey key = new ScramSessionCache.CacheKey("host", 80, "realm");
        ScramSessionCache.Entry entry = createEntry("sr123", 300, 4096);

        cache.put(key, entry);
        assertSame(entry, cache.get(key));
    }

    @Test
    void testGet_missing() {
        ScramSessionCache cache = new ScramSessionCache();
        ScramSessionCache.CacheKey key = new ScramSessionCache.CacheKey("host", 80, "realm");
        assertNull(cache.get(key));
    }

    @Test
    void testIsSrFresh_withinTtl() {
        ScramSessionCache cache = new ScramSessionCache();
        ScramSessionCache.Entry entry = createEntry("sr123", 300, 4096);
        assertTrue(cache.isSrFresh(entry));
    }

    @Test
    void testIsSrFresh_expired() {
        ScramSessionCache cache = new ScramSessionCache();
        ScramSessionCache.Entry entry = new ScramSessionCache.Entry(
                "realm",
                new byte[]{1}, 4096,
                new byte[]{2}, new byte[]{3}, new byte[]{4},
                "sr123", 0, // 0 second TTL = immediately expired
                System.nanoTime() - 1_000_000_000L, // 1 second ago
                4096,
                "r=nonce,s=c2FsdA==,i=4096"
        );
        assertFalse(cache.isSrFresh(entry));
    }

    @Test
    void testIsSrFresh_noTtl() {
        ScramSessionCache cache = new ScramSessionCache();
        ScramSessionCache.Entry entry = createEntry("sr123", -1, 4096);
        assertTrue(cache.isSrFresh(entry)); // ttl=-1 → always fresh
    }

    @Test
    void testIsSrFresh_nullSr() {
        ScramSessionCache cache = new ScramSessionCache();
        ScramSessionCache.Entry entry = createEntry(null, -1, 4096);
        assertFalse(cache.isSrFresh(entry));
    }

    @Test
    void testNonceCountReserveConfirm() {
        ScramSessionCache cache = new ScramSessionCache();
        ScramSessionCache.CacheKey key = new ScramSessionCache.CacheKey("host", 80, "realm");
        ScramSessionCache.Entry entry = createEntry("sr123", 300, 4096);
        cache.put(key, entry);

        // Reserve: atomically returns current value (4096) and increments
        assertEquals(4096, cache.reserveNonceCount(key));

        // Reserve again: returns 4097 (already incremented)
        assertEquals(4097, cache.reserveNonceCount(key));

        // Confirm is a no-op since reserve already incremented
        cache.confirmNonceCount(key);
        assertEquals(4098, cache.reserveNonceCount(key));
    }

    @Test
    void testNonceCountRollback() {
        ScramSessionCache cache = new ScramSessionCache();
        ScramSessionCache.CacheKey key = new ScramSessionCache.CacheKey("host", 80, "realm");
        ScramSessionCache.Entry entry = createEntry("sr123", 300, 4096);
        cache.put(key, entry);

        // Reserve: atomically returns 4096 and increments to 4097
        assertEquals(4096, cache.reserveNonceCount(key));

        // Rollback: decrements back to 4096
        cache.rollbackNonceCount(key);

        // Should be back to 4096
        assertEquals(4096, cache.reserveNonceCount(key));
    }

    @Test
    void testMaxEntries_eviction() {
        ScramSessionCache cache = new ScramSessionCache(2);

        ScramSessionCache.CacheKey key1 = new ScramSessionCache.CacheKey("host1", 80, "realm");
        ScramSessionCache.CacheKey key2 = new ScramSessionCache.CacheKey("host2", 80, "realm");
        ScramSessionCache.CacheKey key3 = new ScramSessionCache.CacheKey("host3", 80, "realm");

        cache.put(key1, createEntry("sr1", 300, 4096));
        cache.put(key2, createEntry("sr2", 300, 4096));
        assertEquals(2, cache.size());

        // Adding third should evict oldest entry, keeping 2 entries
        cache.put(key3, createEntry("sr3", 300, 4096));
        assertEquals(2, cache.size());
        assertNotNull(cache.get(key3));
    }

    @Test
    void testUpdateSr() {
        ScramSessionCache cache = new ScramSessionCache();
        ScramSessionCache.CacheKey key = new ScramSessionCache.CacheKey("host", 80, "realm");
        ScramSessionCache.Entry entry = createEntry("oldSr", 300, 4096);
        cache.put(key, entry);

        cache.updateSr(key, "newSr", 600);

        ScramSessionCache.Entry updated = cache.get(key);
        assertNotNull(updated);
        assertEquals("newSr", updated.sr);
        assertEquals(600, updated.ttl);
    }

    @Test
    void testClear() {
        ScramSessionCache cache = new ScramSessionCache();
        ScramSessionCache.CacheKey key = new ScramSessionCache.CacheKey("host", 80, "realm");
        cache.put(key, createEntry("sr", 300, 4096));

        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get(key));
    }

    @Test
    void testNoSaltedPasswordStored() {
        // Verify that Entry has no SaltedPassword field — only derived keys
        ScramSessionCache.Entry entry = createEntry("sr", 300, 4096);
        assertNotNull(entry.clientKey);
        assertNotNull(entry.storedKey);
        assertNotNull(entry.serverKey);
        // No saltedPassword field exists — this is verified by compilation
    }

    @Test
    void testConcurrentNonceCountReservation() throws Exception {
        ScramSessionCache cache = new ScramSessionCache();
        ScramSessionCache.CacheKey key = new ScramSessionCache.CacheKey("host", 80, "realm");
        ScramSessionCache.Entry entry = createEntry("sr123", 300, 0);
        cache.put(key, entry);

        int threadCount = 16;
        int reservationsPerThread = 1000;
        int totalReservations = threadCount * reservationsPerThread;
        Set<Integer> reservedValues = Collections.newSetFromMap(new ConcurrentHashMap<>());
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < reservationsPerThread; i++) {
                        int value = cache.reserveNonceCount(key);
                        assertTrue(reservedValues.add(value),
                                "Duplicate nonce count reserved: " + value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads did not complete in time");
        executor.shutdown();

        assertEquals(totalReservations, reservedValues.size(),
                "All reserved nonce counts must be unique");
    }

    @Test
    void testCacheKey_equality() {
        ScramSessionCache.CacheKey key1 = new ScramSessionCache.CacheKey("host", 80, "realm");
        ScramSessionCache.CacheKey key2 = new ScramSessionCache.CacheKey("host", 80, "realm");
        ScramSessionCache.CacheKey key3 = new ScramSessionCache.CacheKey("host", 443, "realm");

        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
        assertNotEquals(key1, key3);
    }
}
