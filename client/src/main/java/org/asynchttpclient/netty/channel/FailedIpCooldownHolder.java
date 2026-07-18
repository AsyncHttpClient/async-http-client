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
import java.util.function.LongSupplier;

/**
 * Per-host failed-IP cooldown applied to a request's resolved addresses before a new connection is
 * opened, independently of the configured {@link org.asynchttpclient.LoadBalance} mode.
 *
 * <p>When a connection attempt to an address fails, {@link #markFailed(String, InetSocketAddress)}
 * puts that address in a short cooldown. While the cooldown is active {@link #reorder(String, List)}
 * moves the address to the back of the returned list rather than dropping it, so it is still
 * available as a last-resort failover target and is re-probed once the window elapses. This bounds the
 * cost of an IP that silently black-holes packets (drops them with no RST): without the cooldown every
 * new connection targeting it would burn a full {@code connectTimeout} before failing over; with it,
 * only the occasional re-probe pays that cost. (An IP that actively refuses the connection fails over
 * immediately and cheaply, with or without the cooldown.) Liveness remains governed at the DNS/resolver
 * level — the cooldown is only a short-lived dampener, not a health checker.
 *
 * <p>The cooldown only re-orders the resolved addresses; it never removes one, so failover always has
 * somewhere to go. It tracks TCP connect failures only (TLS/handshake failures are not fed back here),
 * matching where address-level failover happens.
 *
 * <p>Per-host state is held in a bounded map (capped at {@value #MAX_TRACKED_HOSTS}); at the cap an
 * arbitrary entry is evicted before a new one is added, so memory stays bounded even for clients that
 * touch very many distinct hosts. Dropping a host's state is harmless — it simply forgets any cooldowns
 * the next time the host is seen.
 *
 * <p>Thread-safe.
 */
public final class FailedIpCooldownHolder {

    // Cap on the number of per-host entries retained, so a client that touches very many distinct
    // multi-IP hosts (crawler/gateway) can't grow this map without bound. At the cap an arbitrary
    // entry is evicted before a new one is inserted (same approach as util/NonceCounter).
    static final int MAX_TRACKED_HOSTS = 4096;

    // How long a failed address is deprioritized before it is re-probed. Deliberately coarser than the
    // default connectTimeout (PT5S) so that a single failure actually routes traffic away from a dead IP
    // for a useful window instead of re-pinning to it on the very next request, yet short enough that a
    // recovered IP rejoins the order quickly. The DNS/resolver layer remains the authority on liveness.
    static final Duration DEFAULT_FAILED_IP_COOLDOWN = Duration.ofSeconds(10);

    private final ConcurrentHashMap<String, HostState> hosts = new ConcurrentHashMap<>();
    private final long cooldownNanos;
    private final LongSupplier nanoClock;

    public FailedIpCooldownHolder() {
        this(DEFAULT_FAILED_IP_COOLDOWN.toNanos(), System::nanoTime);
    }

    public FailedIpCooldownHolder(long cooldownNanos, LongSupplier nanoClock) {
        this.cooldownNanos = cooldownNanos;
        this.nanoClock = nanoClock;
    }

    /**
     * Re-orders {@code addresses} so that any address currently in cooldown is moved to the back
     * (otherwise preserving the incoming order).
     *
     * @param host      the connection's target host (the key the matching {@link #markFailed} calls use)
     * @param addresses the resolved socket addresses, in their incoming order
     * @return the same list instance when there is nothing to do (size {@code <= 1}, or no address is in
     * cooldown), otherwise a new list with the cooling addresses moved to the back
     */
    public List<InetSocketAddress> reorder(String host, List<InetSocketAddress> addresses) {
        if (addresses.size() <= 1) {
            return addresses;
        }
        // Touch the per-host state even when nothing is cooling yet, so a subsequent markFailed for this
        // host (which never resurrects an evicted entry) has somewhere to record the failure.
        HostState state = stateFor(host);
        if (state.cooldowns.isEmpty()) {
            return addresses;
        }
        return moveCoolingToBack(state, addresses);
    }

    /**
     * Records that a connection attempt to {@code address} (for {@code host}) failed, so subsequent
     * {@link #reorder} calls move it to the back for {@link #DEFAULT_FAILED_IP_COOLDOWN}. No-op when
     * the host is not (or no longer) tracked — we never resurrect an evicted entry, which keeps the
     * failure path from growing the map for hosts that are not actively being connected to.
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

    // Stable-partition the order into not-cooling (kept first) and cooling (moved to the back), expiring
    // elapsed cooldowns lazily as we go. If every address is cooling, the input is returned unchanged so we
    // never hand back an empty list — failover still has somewhere to go.
    private List<InetSocketAddress> moveCoolingToBack(HostState state, List<InetSocketAddress> addresses) {
        long now = nanoClock.getAsLong();
        List<InetSocketAddress> healthy = new ArrayList<>(addresses.size());
        List<InetSocketAddress> cooling = null;
        for (InetSocketAddress address : addresses) {
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
            return addresses; // nothing actually cooling (all entries had expired)
        }
        if (healthy.isEmpty()) {
            return addresses; // everything is cooling — keep the original order rather than return nothing
        }
        healthy.addAll(cooling);
        return healthy;
    }

    // Keep the map bounded: when it is full, drop one arbitrary entry before a new host is added.
    // Evicting an entry only forgets that host's cooldowns, so the choice of victim does not matter.
    private void evictIfNeeded() {
        if (hosts.size() >= MAX_TRACKED_HOSTS) {
            var it = hosts.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    // Per-host set of addresses currently in cooldown (address -> nanoTime the cooldown expires). The map
    // is bounded by the host's resolved-IP count and self-prunes as entries expire during reorder.
    private static final class HostState {
        final ConcurrentHashMap<InetSocketAddress, Long> cooldowns = new ConcurrentHashMap<>();
    }
}
