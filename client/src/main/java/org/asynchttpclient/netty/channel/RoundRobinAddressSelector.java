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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

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
 * <p><b>Failed-IP cooldown.</b> When a connection attempt to an address fails, {@link
 * #markFailed(String, InetSocketAddress)} puts that address in a short cooldown. While the cooldown
 * is active {@link #rotate(String, List)} deprioritizes the address — it is moved to the back of the
 * returned list rather than dropped, so it is still available as a last-resort failover target and
 * is re-probed once the window elapses. This bounds the cost of an IP that silently black-holes
 * packets (no RST): without the cooldown every request pinned to it would burn a full
 * {@code connectTimeout} before failing over; with it, only the occasional re-probe pays that cost.
 * Liveness remains governed at the DNS/resolver level — the cooldown is only a short-lived dampener,
 * not a health checker.
 *
 * <p>Per-host state is held in a bounded map (capped at {@value #MAX_TRACKED_HOSTS}); at the cap an
 * arbitrary entry is evicted before a new one is added, so memory stays bounded even for clients that
 * touch very many distinct hosts. Dropping a host's state is harmless — its rotation simply restarts
 * at the first resolved address (and forgets any cooldowns) the next time it is seen.
 *
 * <p>Thread-safe.
 */
public final class RoundRobinAddressSelector {

    // Cap on the number of per-host entries retained, so a client that touches very many distinct
    // multi-IP hosts (crawler/gateway) can't grow this map without bound. At the cap an arbitrary
    // entry is evicted before a new one is inserted (same approach as util/NonceCounter).
    static final int MAX_TRACKED_HOSTS = 4096;

    // How long a failed address is deprioritized before it is re-probed. Deliberately coarser than the
    // default connectTimeout (PT5S) so that a single failure actually routes traffic away from a dead IP
    // for a useful window instead of re-pinning to it on the very next request, yet short enough that a
    // recovered IP rejoins the rotation quickly. The DNS/resolver layer remains the authority on liveness.
    static final Duration DEFAULT_FAILED_IP_COOLDOWN = Duration.ofSeconds(10);

    private final ConcurrentHashMap<String, HostState> hosts = new ConcurrentHashMap<>();
    private final long cooldownNanos;
    private final LongSupplier nanoClock;

    public RoundRobinAddressSelector() {
        this(DEFAULT_FAILED_IP_COOLDOWN.toNanos(), System::nanoTime);
    }

    // Visible for testing: lets tests drive a virtual clock and a custom cooldown deterministically.
    RoundRobinAddressSelector(long cooldownNanos, LongSupplier nanoClock) {
        this.cooldownNanos = cooldownNanos;
        this.nanoClock = nanoClock;
    }

    /**
     * @param host     the request's target host
     * @param resolved the resolved socket addresses (size {@code >= 1}), in resolver order
     * @return the same list instance when there is nothing to rotate (size {@code <= 1}, or the
     * selected index is already first and no address is in cooldown), otherwise a new list whose first
     * element is the round-robin-selected address, with any addresses currently in cooldown moved to the
     * back (otherwise preserving resolver order)
     */
    public List<InetSocketAddress> rotate(String host, List<InetSocketAddress> resolved) {
        int n = resolved.size();
        if (n <= 1) {
            return resolved;
        }

        HostState state = stateFor(host);
        int index = (state.counter.getAndIncrement() & Integer.MAX_VALUE) % n;

        // Fast path: nothing failed recently, so the order is the plain round-robin rotation.
        if (state.cooldowns.isEmpty()) {
            return index == 0 ? resolved : rotateBy(resolved, index, n);
        }

        List<InetSocketAddress> rotated = index == 0 ? resolved : rotateBy(resolved, index, n);
        return deprioritizeCooling(state, rotated);
    }

    /**
     * Records that a connection attempt to {@code address} (for {@code host}) failed, so subsequent
     * rotations deprioritize it for {@link #DEFAULT_FAILED_IP_COOLDOWN}. No-op when the host is not (or
     * no longer) tracked — we never resurrect an evicted entry, which keeps the failure path from
     * growing the map for hosts that round-robin is not actively rotating.
     */
    public void markFailed(String host, InetSocketAddress address) {
        HostState state = hosts.get(host);
        if (state != null) {
            state.cooldowns.put(address, nanoClock.getAsLong() + cooldownNanos);
        }
    }

    // Visible for testing: the number of hosts currently tracked (bounded by MAX_TRACKED_HOSTS).
    int trackedHostCount() {
        return hosts.size();
    }

    private HostState stateFor(String host) {
        evictIfNeeded();
        return hosts.computeIfAbsent(host, h -> new HostState());
    }

    private static List<InetSocketAddress> rotateBy(List<InetSocketAddress> resolved, int index, int n) {
        List<InetSocketAddress> rotated = new ArrayList<>(n);
        rotated.addAll(resolved.subList(index, n));
        rotated.addAll(resolved.subList(0, index));
        return rotated;
    }

    // Stable-partition the rotated order into not-cooling (kept first) and cooling (moved to the back),
    // expiring elapsed cooldowns lazily as we go. If every address is cooling, the rotation is returned
    // unchanged so we never hand back an empty list — failover still has somewhere to go.
    private List<InetSocketAddress> deprioritizeCooling(HostState state, List<InetSocketAddress> rotated) {
        long now = nanoClock.getAsLong();
        List<InetSocketAddress> healthy = new ArrayList<>(rotated.size());
        List<InetSocketAddress> cooling = null;
        for (InetSocketAddress address : rotated) {
            Long until = state.cooldowns.get(address);
            if (until == null) {
                healthy.add(address);
            } else if (until - now > 0) { // nanoTime-safe comparison
                if (cooling == null) {
                    cooling = new ArrayList<>();
                }
                cooling.add(address);
            } else {
                state.cooldowns.remove(address, until);
                healthy.add(address);
            }
        }
        if (cooling == null) {
            return rotated; // nothing actually cooling (all entries had expired)
        }
        if (healthy.isEmpty()) {
            return rotated; // everything is cooling — keep the plain rotation rather than return nothing
        }
        healthy.addAll(cooling);
        return healthy;
    }

    // Keep the map bounded: when it is full, drop one arbitrary entry before a new host is added.
    // Evicting an entry only resets that host's rotation and cooldowns, so the choice of victim does not matter.
    private void evictIfNeeded() {
        if (hosts.size() >= MAX_TRACKED_HOSTS) {
            var it = hosts.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    // Per-host rotation cursor plus the set of addresses currently in cooldown (address -> nanoTime the
    // cooldown expires). The cooldown map is bounded by the host's resolved-IP count and self-prunes as
    // entries expire during rotation.
    private static final class HostState {
        final AtomicInteger counter = new AtomicInteger();
        final ConcurrentHashMap<InetSocketAddress, Long> cooldowns = new ConcurrentHashMap<>();
    }
}
