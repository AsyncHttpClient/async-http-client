/*
 *    Copyright (c) 2015-2026 AsyncHttpClient Project. All rights reserved.
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

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundRobinAddressSelectorTest {

    private static InetSocketAddress addr(String ip) {
        return new InetSocketAddress(ip, 80);
    }

    private static String firstIp(List<InetSocketAddress> addresses) {
        return addresses.get(0).getAddress().getHostAddress();
    }

    @Test
    void rotatesThroughAllAddressesEvenly() {
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector();
        // the resolver order is used as-is (not re-sorted); rotation cycles through every address
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.3"), addr("127.0.0.1"), addr("127.0.0.2"));

        int rounds = 30;
        Map<String, Integer> firstCounts = new HashMap<>();
        for (int i = 0; i < rounds * 3; i++) {
            List<InetSocketAddress> rotated = selector.rotate("host", input);
            assertEquals(3, rotated.size());
            // every address is preserved so the connector can still fail over
            assertTrue(rotated.containsAll(input));
            firstCounts.merge(firstIp(rotated), 1, Integer::sum);
        }

        assertEquals(3, firstCounts.size(), "all 3 IPs should be selected first at some point");
        for (int count : firstCounts.values()) {
            assertEquals(rounds, count, "round-robin should select each IP first an equal number of times");
        }
    }

    @Test
    void rotationFollowsResolverOrder() {
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector();
        // the address order is taken as-is from the resolver; rotation steps through it in that order
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.3"), addr("127.0.0.1"), addr("127.0.0.2"));

        assertEquals("127.0.0.3", firstIp(selector.rotate("h", input)));
        assertEquals("127.0.0.1", firstIp(selector.rotate("h", input)));
        assertEquals("127.0.0.2", firstIp(selector.rotate("h", input)));
        assertEquals("127.0.0.3", firstIp(selector.rotate("h", input)));
    }

    @Test
    void rotatedViewMatchesFullLeftRotation() {
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector();
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.1"), addr("127.0.0.2"),
                addr("127.0.0.3"), addr("127.0.0.4"));
        int n = input.size();
        // Each successive rotation is the resolver order rotated left by one more; the returned view must
        // reproduce the whole order element-by-element (not just the first element).
        for (int start = 0; start < n; start++) {
            List<InetSocketAddress> rotated = selector.rotate("h", input);
            assertEquals(n, rotated.size());
            for (int i = 0; i < n; i++) {
                assertEquals(input.get((start + i) % n), rotated.get(i),
                        "element " + i + " of the rotation starting at index " + start);
            }
        }
    }

    @Test
    void rotatedResultIsAReadOnlyView() {
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector();
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.1"), addr("127.0.0.2"), addr("127.0.0.3"));
        selector.rotate("h", input);                          // index 0 -> returns input as-is
        List<InetSocketAddress> rotated = selector.rotate("h", input); // index 1 -> a real rotation
        assertNotSame(input, rotated, "a non-zero rotation returns a distinct view");
        assertThrows(UnsupportedOperationException.class, () -> rotated.set(0, addr("127.0.0.9")),
                "the rotated view must be read-only (all consumers only read it)");
    }

    @Test
    void singleAddressReturnedUnchanged() {
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector();
        List<InetSocketAddress> input = Collections.singletonList(addr("127.0.0.1"));
        assertSame(input, selector.rotate("host", input));
    }

    @Test
    void boundsTrackedHosts() {
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector();
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.1"), addr("127.0.0.2"));

        // Drive far more distinct hosts through the selector than the cap allows; an arbitrary entry is
        // evicted before each new host is added once the map is full, so the tracker stays bounded.
        for (int i = 0; i < RoundRobinAddressSelector.MAX_TRACKED_HOSTS * 3; i++) {
            selector.rotate("host-" + i, input);
        }

        assertTrue(selector.trackedHostCount() <= RoundRobinAddressSelector.MAX_TRACKED_HOSTS,
                "tracked hosts must stay bounded by the cap");
    }

    @Test
    void perHostCountersAreIndependent() {
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector();
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.1"), addr("127.0.0.2"));

        // both hosts start at index 0 of the resolver order
        assertEquals("127.0.0.1", firstIp(selector.rotate("a", input)));
        assertEquals("127.0.0.1", firstIp(selector.rotate("b", input)));
        // advancing host "a" must not affect host "b"
        assertEquals("127.0.0.2", firstIp(selector.rotate("a", input)));
        assertNotEquals(firstIp(selector.rotate("b", input)), "127.0.0.1");
    }

    @Test
    void failedAddressIsDeprioritizedDuringCooldown() {
        long[] now = {1_000};
        LongSupplier clock = () -> now[0];
        // cooldown of 100 ticks, driven by a virtual clock so the test is deterministic
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector(100, clock);
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.1"), addr("127.0.0.2"));

        selector.rotate("h", input);                 // index 0 -> 127.0.0.1 first, counter now 1
        selector.markFailed("h", addr("127.0.0.1")); // 127.0.0.1 enters cooldown until tick 1100

        // index 1 -> 127.0.0.2 naturally first
        assertEquals("127.0.0.2", firstIp(selector.rotate("h", input)));
        // index 0 would put the cooling 127.0.0.1 first, but it is deprioritized to the back
        List<InetSocketAddress> rotated = selector.rotate("h", input);
        assertEquals("127.0.0.2", firstIp(rotated));
        assertTrue(rotated.containsAll(input), "the cooling address is kept as a last-resort failover target");
    }

    @Test
    void cooledAddressIsReprobedAfterWindowElapses() {
        long[] now = {1_000};
        LongSupplier clock = () -> now[0];
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector(100, clock);
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.1"), addr("127.0.0.2"));

        selector.rotate("h", input);                 // counter now 1
        selector.markFailed("h", addr("127.0.0.1")); // cooldown until tick 1100
        now[0] = 1_201;                              // advance well past the cooldown window

        // once the window has elapsed the address rejoins the plain rotation and can be selected first again
        boolean reprobed = false;
        for (int i = 0; i < 2 && !reprobed; i++) {
            reprobed = "127.0.0.1".equals(firstIp(selector.rotate("h", input)));
        }
        assertTrue(reprobed, "a recovered IP must be re-probed after its cooldown elapses");
    }

    @Test
    void allAddressesCoolingFallsBackToPlainRotation() {
        long[] now = {1_000};
        LongSupplier clock = () -> now[0];
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector(100, clock);
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.1"), addr("127.0.0.2"));

        selector.rotate("h", input);
        selector.markFailed("h", addr("127.0.0.1"));
        selector.markFailed("h", addr("127.0.0.2"));

        // every address is cooling — rotation must still hand back the full list, never an empty one
        List<InetSocketAddress> rotated = selector.rotate("h", input);
        assertEquals(2, rotated.size());
        assertTrue(rotated.containsAll(input));
    }

    @Test
    void markFailedForUntrackedHostIsNoOp() {
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector();
        // a host that was never rotated must not be resurrected (or have memory allocated) by a failure
        selector.markFailed("never-rotated", addr("127.0.0.1"));
        assertEquals(0, selector.trackedHostCount());
    }
}
