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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Picks, per host and per request, which resolved IP a new connection should target first when
 * {@link org.asynchttpclient.LoadBalance#ROUND_ROBIN} is enabled.
 *
 * <p>{@link #rotate(String, List)} returns the resolved addresses re-ordered so that the
 * round-robin-selected address comes first; the remaining addresses follow (in their original
 * order) so the connector can still fail over to them. The order is taken as-is from the resolver
 * and is not re-sorted, so the per-host counter maps consistently to the same address across
 * requests only when the configured {@link io.netty.resolver.InetNameResolver} returns the
 * addresses in a stable order (see {@link org.asynchttpclient.LoadBalance#ROUND_ROBIN}).
 *
 * <p>This class is concerned only with rotation. Deprioritizing addresses whose connection attempts
 * recently failed is handled separately and mode-independently by {@link FailedIpCooldownHolder}, applied
 * on top of the rotation before a connection is opened.
 *
 * <p>Per-host state is held in a bounded map (capped at {@value #MAX_TRACKED_HOSTS}); at the cap an
 * arbitrary entry is evicted before a new one is added, so memory stays bounded even for clients that
 * touch very many distinct hosts. Dropping a host's state is harmless — its rotation simply restarts
 * at the first resolved address the next time it is seen.
 *
 * <p>Thread-safe.
 */
public final class RoundRobinAddressSelector {

    // Cap on the number of per-host entries retained, so a client that touches very many distinct
    // multi-IP hosts (crawler/gateway) can't grow this map without bound. At the cap an arbitrary
    // entry is evicted before a new one is inserted (same approach as util/NonceCounter).
    static final int MAX_TRACKED_HOSTS = 4096;

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * @param host     the request's target host
     * @param resolved the resolved socket addresses (size {@code >= 1}), in resolver order
     * @return the same list instance when there is nothing to rotate (size {@code <= 1}, or the
     * selected index is already first), otherwise a new list whose first element is the
     * round-robin-selected address (otherwise preserving resolver order)
     */
    public List<InetSocketAddress> rotate(String host, List<InetSocketAddress> resolved) {
        int n = resolved.size();
        if (n <= 1) {
            return resolved;
        }

        AtomicInteger counter = counterFor(host);
        int index = (counter.getAndIncrement() & Integer.MAX_VALUE) % n;
        return index == 0 ? resolved : rotateBy(resolved, index, n);
    }

    // Visible for testing: the number of hosts currently tracked (bounded by MAX_TRACKED_HOSTS).
    int trackedHostCount() {
        return counters.size();
    }

    private AtomicInteger counterFor(String host) {
        evictIfNeeded();
        return counters.computeIfAbsent(host, h -> new AtomicInteger());
    }

    private static List<InetSocketAddress> rotateBy(List<InetSocketAddress> resolved, int index, int n) {
        List<InetSocketAddress> rotated = new ArrayList<>(n);
        rotated.addAll(resolved.subList(index, n));
        rotated.addAll(resolved.subList(0, index));
        return rotated;
    }

    // Keep the map bounded: when it is full, drop one arbitrary entry before a new host is added.
    // Evicting an entry only resets that host's rotation, so the choice of victim does not matter.
    private void evictIfNeeded() {
        if (counters.size() >= MAX_TRACKED_HOSTS) {
            var it = counters.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }
}
