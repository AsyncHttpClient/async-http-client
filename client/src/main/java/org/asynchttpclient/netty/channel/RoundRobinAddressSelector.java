/*
 *    Copyright (c) 2024 AsyncHttpClient Project. All rights reserved.
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
import java.util.concurrent.ConcurrentHashMap;
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

    private static final Comparator<InetSocketAddress> STABLE_ORDER = Comparator.comparing(address -> {
        if (address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return address.getHostString();
    });

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

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

    private int nextCount(String host) {
        return counters.computeIfAbsent(host, h -> new AtomicInteger()).getAndIncrement();
    }
}
