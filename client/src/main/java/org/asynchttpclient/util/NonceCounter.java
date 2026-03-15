/*
 * Copyright (c) 2025 AsyncHttpClient Project. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asynchttpclient.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe nonce count tracker for HTTP Digest Authentication (RFC 7616).
 * Tracks the number of times each nonce has been used and returns the
 * next nc value as an 8-digit hex string.
 */
public class NonceCounter {

    private static final int MAX_ENTRIES = 100;

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * Returns the next nc value for the given nonce as an 8-digit hex string,
     * atomically incrementing the counter.
     */
    public String nextNc(String nonce) {
        evictIfNeeded();
        AtomicInteger counter = counters.computeIfAbsent(nonce, k -> new AtomicInteger(0));
        int nc = counter.incrementAndGet();
        return String.format("%08x", nc);
    }

    /**
     * Removes tracking for the given nonce (e.g., on nextnonce rotation).
     */
    public void reset(String nonce) {
        counters.remove(nonce);
    }

    private void evictIfNeeded() {
        if (counters.size() >= MAX_ENTRIES) {
            // Simple eviction: remove an arbitrary entry
            var it = counters.entrySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }
}
