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

import org.jetbrains.annotations.Nullable;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Chooses the address for each connection attempt in {@link org.asynchttpclient.LoadBalance#LEAST_CONNECTIONS}
 * mode, and tracks how many connections this client has open, or is opening, to each address.
 *
 * <p>Counts are keyed by resolved socket address and shared by the whole client. They outlive a re-resolution: an
 * address that drops out of the resolver's answer keeps its count until its connections close, but only the
 * addresses passed to {@link #selectAndAcquire} are candidates. An entry is removed when its count reaches zero, so
 * the map never holds more entries than there are open connections.
 *
 * <p>Candidates are compared on, in order:
 * <ol>
 *   <li>the failed-IP cooldown ({@link FailedIpCooldownHolder}): cooling addresses go last;</li>
 *   <li>failed attempts of this connection in the address's family (IPv4 or IPv6), fewer first; always zero on
 *       the first attempt;</li>
 *   <li>open connections, fewer first;</li>
 *   <li>position in the given list, earlier first.</li>
 * </ol>
 *
 * <p>Thread-safe. Every {@link #selectAndAcquire} must be matched by exactly one {@link #release}.
 */
public final class LeastConnectionsAddressSelector {

    private final @Nullable FailedIpCooldownHolder cooldown;
    // Guarded by this. A one-element int[] is a mutable counter that avoids boxing.
    private final Map<InetSocketAddress, int[]> counts = new HashMap<>();

    public LeastConnectionsAddressSelector(@Nullable FailedIpCooldownHolder cooldown) {
        this.cooldown = cooldown;
    }

    /**
     * Picks the address for the next attempt from those not tried yet, counts a connection to it, and moves it to
     * index {@code from}. The other untried addresses keep their relative order.
     *
     * @param host      the target host, which keys the failed-IP cooldown
     * @param addresses the host's resolved addresses; reordered in place, so the caller must own the list
     * @param from      the number of leading addresses already tried and failed; less than {@code addresses.size()}
     * @return the picked address
     */
    synchronized InetSocketAddress selectAndAcquire(String host, List<InetSocketAddress> addresses, int from) {
        Predicate<InetSocketAddress> cooling = cooldown != null
                ? cooldown.coolingNow(host)
                : FailedIpCooldownHolder.NOT_COOLING;
        int ipv4Failures = 0;
        int ipv6Failures = 0;
        for (int i = 0; i < from; i++) {
            if (isIpv6(addresses.get(i))) {
                ipv6Failures++;
            } else {
                ipv4Failures++;
            }
        }
        int selected = -1;
        boolean selectedCooling = false;
        int selectedFamilyFailures = 0;
        int selectedCount = 0;
        for (int i = from; i < addresses.size(); i++) {
            InetSocketAddress address = addresses.get(i);
            boolean addressCooling = cooling.test(address);
            int familyFailures = isIpv6(address) ? ipv6Failures : ipv4Failures;
            int count = count(address);
            boolean better;
            if (selected < 0) {
                better = true;
            } else if (addressCooling != selectedCooling) {
                better = selectedCooling;
            } else if (familyFailures != selectedFamilyFailures) {
                better = familyFailures < selectedFamilyFailures;
            } else {
                better = count < selectedCount;
            }
            if (better) {
                selected = i;
                selectedCooling = addressCooling;
                selectedFamilyFailures = familyFailures;
                selectedCount = count;
            }
        }
        InetSocketAddress address = addresses.get(selected);
        counts.computeIfAbsent(address, a -> new int[1])[0]++;
        if (selected != from) {
            Collections.rotate(addresses.subList(from, selected + 1), 1);
        }
        return address;
    }

    synchronized void release(InetSocketAddress address) {
        int[] count = counts.get(address);
        if (count != null && --count[0] <= 0) {
            counts.remove(address);
        }
    }

    // Visible for testing.
    synchronized int activeConnections(InetSocketAddress address) {
        return count(address);
    }

    private int count(InetSocketAddress address) {
        int[] count = counts.get(address);
        return count != null ? count[0] : 0;
    }

    private static boolean isIpv6(InetSocketAddress address) {
        return address.getAddress() instanceof Inet6Address;
    }
}
