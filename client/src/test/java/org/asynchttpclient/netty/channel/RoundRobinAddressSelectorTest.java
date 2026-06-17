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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        // intentionally unsorted input; the selector sorts for a stable rotation order
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
    void rotationFollowsStableSortedOrder() {
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector();
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.3"), addr("127.0.0.1"), addr("127.0.0.2"));

        assertEquals("127.0.0.1", firstIp(selector.rotate("h", input)));
        assertEquals("127.0.0.2", firstIp(selector.rotate("h", input)));
        assertEquals("127.0.0.3", firstIp(selector.rotate("h", input)));
        assertEquals("127.0.0.1", firstIp(selector.rotate("h", input)));
    }

    @Test
    void singleAddressReturnedUnchanged() {
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector();
        List<InetSocketAddress> input = Collections.singletonList(addr("127.0.0.1"));
        assertSame(input, selector.rotate("host", input));
    }

    @Test
    void perHostCountersAreIndependent() {
        RoundRobinAddressSelector selector = new RoundRobinAddressSelector();
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.1"), addr("127.0.0.2"));

        // both hosts start at index 0 of the sorted order
        assertEquals("127.0.0.1", firstIp(selector.rotate("a", input)));
        assertEquals("127.0.0.1", firstIp(selector.rotate("b", input)));
        // advancing host "a" must not affect host "b"
        assertEquals("127.0.0.2", firstIp(selector.rotate("a", input)));
        assertNotEquals(firstIp(selector.rotate("b", input)), "127.0.0.1");
    }
}
