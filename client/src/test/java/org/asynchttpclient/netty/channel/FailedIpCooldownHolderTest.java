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

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailedIpCooldownHolderTest {

    private static InetSocketAddress addr(String ip) {
        return new InetSocketAddress(ip, 80);
    }

    private static String firstIp(List<InetSocketAddress> addresses) {
        return addresses.get(0).getAddress().getHostAddress();
    }

    @Test
    void failedAddressIsDeprioritizedDuringCooldown() {
        long[] now = {1_000};
        LongSupplier clock = () -> now[0];
        // cooldown of 100 ticks, driven by a virtual clock so the test is deterministic
        FailedIpCooldownHolder cooldown = new FailedIpCooldownHolder(100, clock);
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.1"), addr("127.0.0.2"));

        cooldown.reorder("h", input);              // create per-host state so markFailed is recorded
        cooldown.markFailed("h", addr("127.0.0.1"));    // 127.0.0.1 enters cooldown until tick 1100

        // the cooling 127.0.0.1 is moved to the back; the healthy 127.0.0.2 comes first
        List<InetSocketAddress> ordered = cooldown.reorder("h", input);
        assertEquals("127.0.0.2", firstIp(ordered));
        assertTrue(ordered.containsAll(input), "the cooling address is kept as a last-resort failover target");
    }

    @Test
    void cooledAddressIsReprobedAfterWindowElapses() {
        long[] now = {1_000};
        LongSupplier clock = () -> now[0];
        FailedIpCooldownHolder cooldown = new FailedIpCooldownHolder(100, clock);
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.1"), addr("127.0.0.2"));

        cooldown.reorder("h", input);              // create state
        cooldown.markFailed("h", addr("127.0.0.1"));    // cooldown until tick 1100
        now[0] = 1_201;                                 // advance well past the cooldown window

        // once the window has elapsed the address rejoins the order in its original position
        assertEquals("127.0.0.1", firstIp(cooldown.reorder("h", input)),
                "a recovered IP must be re-probed after its cooldown elapses");
    }

    @Test
    void allAddressesCoolingFallsBackToOriginalOrder() {
        long[] now = {1_000};
        LongSupplier clock = () -> now[0];
        FailedIpCooldownHolder cooldown = new FailedIpCooldownHolder(100, clock);
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.1"), addr("127.0.0.2"));

        cooldown.reorder("h", input);
        cooldown.markFailed("h", addr("127.0.0.1"));
        cooldown.markFailed("h", addr("127.0.0.2"));

        // every address is cooling — must still hand back the full list, never an empty one
        List<InetSocketAddress> ordered = cooldown.reorder("h", input);
        assertEquals(2, ordered.size());
        assertTrue(ordered.containsAll(input));
    }

    @Test
    void markFailedForUntrackedHostIsNoOp() {
        FailedIpCooldownHolder cooldown = new FailedIpCooldownHolder(100, () -> 0L);
        // a host that was never reordered must not be resurrected (or have memory allocated) by a failure
        cooldown.markFailed("never-reordered", addr("127.0.0.1"));
        assertEquals(0, cooldown.trackedHostCount());
    }

    @Test
    void singleAddressReturnedUnchangedAndUntracked() {
        FailedIpCooldownHolder cooldown = new FailedIpCooldownHolder(100, () -> 0L);
        List<InetSocketAddress> input = Collections.singletonList(addr("127.0.0.1"));
        // size <= 1 short-circuits: same instance back, and no per-host state allocated
        assertSame(input, cooldown.reorder("h", input));
        assertEquals(0, cooldown.trackedHostCount());
    }

    @Test
    void reorderWithoutFailuresReturnsInputUnchanged() {
        FailedIpCooldownHolder cooldown = new FailedIpCooldownHolder(100, () -> 0L);
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.1"), addr("127.0.0.2"));
        // nothing has failed yet, so the order is preserved (same instance returned on the fast path)
        assertSame(input, cooldown.reorder("h", input));
    }

    @Test
    void boundsTrackedHosts() {
        FailedIpCooldownHolder cooldown = new FailedIpCooldownHolder(100, () -> 0L);
        List<InetSocketAddress> input = Arrays.asList(addr("127.0.0.1"), addr("127.0.0.2"));

        // Drive far more distinct hosts through the cooldown than the cap allows; an arbitrary entry is
        // evicted before each new host is added once the map is full, so the tracker stays bounded.
        for (int i = 0; i < FailedIpCooldownHolder.MAX_TRACKED_HOSTS * 3; i++) {
            cooldown.reorder("host-" + i, input);
        }

        assertTrue(cooldown.trackedHostCount() <= FailedIpCooldownHolder.MAX_TRACKED_HOSTS,
                "tracked hosts must stay bounded by the cap");
    }
}
