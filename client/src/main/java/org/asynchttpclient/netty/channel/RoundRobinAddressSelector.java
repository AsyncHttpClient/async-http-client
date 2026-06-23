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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Picks, per host and per request, which resolved IP a new connection should target first when
 * {@link org.asynchttpclient.RequestSendType#ROUND_ROBIN} is enabled.
 *
 * <p>{@link #rotate(String, List)} returns the resolved addresses re-ordered so that the
 * round-robin-selected address comes first; the remaining addresses follow (in a stable order) so
 * the connector can still fail over to them. The addresses are sorted into a stable order before
 * rotation so that the per-host counter maps consistently to the same address across requests,
 * regardless of the order the resolver returns them in.
 *
 * <p>Thread-safe.
 */
public final class RoundRobinAddressSelector {

    // Cap on the number of per-host counters retained, so a client that touches very many distinct
    // multi-IP hosts (crawler/gateway) can't grow this map without bound. When exceeded, the
    // least-recently-used hosts are evicted down to LOW_WATER_MARK.
    static final int MAX_TRACKED_HOSTS = 4096;
    private static final int LOW_WATER_MARK = MAX_TRACKED_HOSTS * 9 / 10;

    private static final Comparator<InetSocketAddress> STABLE_ORDER = Comparator.comparing(address -> {
        if (address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return address.getHostString();
    });

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    // Guards eviction so only one thread sweeps at a time; the rest proceed without blocking.
    private final AtomicBoolean evicting = new AtomicBoolean();

    /**
     * @param host     the request's target host
     * @param resolved the resolved socket addresses (size {@code >= 1})
     * @return the same list instance when there is nothing to rotate (size {@code <= 1}), otherwise
     * a new list whose first element is the round-robin-selected address
     */
    public List<InetSocketAddress> rotate(String host, List<InetSocketAddress> resolved) {
        int n = resolved.size();
        if (n <= 1) {
            return resolved;
        }

        List<InetSocketAddress> ordered = new ArrayList<>(resolved);
        ordered.sort(STABLE_ORDER);

        int index = (nextCount(host) & Integer.MAX_VALUE) % n;
        if (index == 0) {
            return ordered;
        }

        List<InetSocketAddress> rotated = new ArrayList<>(n);
        rotated.addAll(ordered.subList(index, n));
        rotated.addAll(ordered.subList(0, index));
        return rotated;
    }

    // Visible for testing: the number of hosts currently tracked (bounded by MAX_TRACKED_HOSTS).
    int trackedHostCount() {
        return counters.size();
    }

    private int nextCount(String host) {
        Counter counter = counters.computeIfAbsent(host, h -> new Counter());
        counter.lastAccessNanos = System.nanoTime();
        int value = counter.value.getAndIncrement();
        if (counters.size() > MAX_TRACKED_HOSTS) {
            evictOldest();
        }
        return value;
    }

    // Evict the least-recently-used hosts (by last-access time) down to LOW_WATER_MARK. Only one
    // thread sweeps at a time; the rest skip and proceed, so the map may briefly exceed
    // MAX_TRACKED_HOSTS. Dropping a host's counter is harmless: its rotation simply restarts at the
    // first address the next time the host is seen.
    private void evictOldest() {
        if (!evicting.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Map.Entry<String, Counter>> entries = new ArrayList<>(counters.entrySet());
            int excess = entries.size() - LOW_WATER_MARK;
            if (excess <= 0) {
                return;
            }
            entries.sort(Comparator.comparingLong(entry -> entry.getValue().lastAccessNanos));
            for (int i = 0; i < excess; i++) {
                Map.Entry<String, Counter> eldest = entries.get(i);
                // Remove only if still mapped to the same Counter, so a host re-created after the
                // snapshot isn't dropped along with its stale entry.
                counters.remove(eldest.getKey(), eldest.getValue());
            }
        } finally {
            evicting.set(false);
        }
    }

    private static final class Counter {
        final AtomicInteger value = new AtomicInteger();
        volatile long lastAccessNanos = System.nanoTime();
    }
}
