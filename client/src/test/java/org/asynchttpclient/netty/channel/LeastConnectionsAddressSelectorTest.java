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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LeastConnectionsAddressSelectorTest {

    private static final String HOST = "lc.test";

    private static final InetSocketAddress A = address(1);
    private static final InetSocketAddress B = address(2);
    private static final InetSocketAddress C = address(3);
    private static final InetSocketAddress V6 = ipv6Address(1);
    private static final InetSocketAddress V6B = ipv6Address(2);
    private static final InetSocketAddress V6C = ipv6Address(3);

    private static InetSocketAddress address(int lastOctet) {
        try {
            return new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, (byte) lastOctet}), 443);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InetSocketAddress ipv6Address(int lastByte) {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) 0xfd;
        bytes[15] = (byte) lastByte;
        try {
            return new InetSocketAddress(InetAddress.getByAddress(bytes), 443);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    // Counts a connection to the address, as an attempt that selected it would.
    private static void open(LeastConnectionsAddressSelector selector, InetSocketAddress address) {
        selector.selectAndAcquire(HOST, new ArrayList<>(List.of(address)), 0);
    }

    private static InetSocketAddress first(LeastConnectionsAddressSelector selector, List<InetSocketAddress> addresses) {
        return selector.selectAndAcquire(HOST, new ArrayList<>(addresses), 0);
    }

    // The list as the connector holds it after a pick: a copy of the given one, reordered in place.
    private static List<InetSocketAddress> select(LeastConnectionsAddressSelector selector, List<InetSocketAddress> addresses,
                                                  int from) {
        List<InetSocketAddress> copy = new ArrayList<>(addresses);
        InetSocketAddress selected = selector.selectAndAcquire(HOST, copy, from);
        assertEquals(selected, copy.get(from), "the selection is moved to position from");
        return copy;
    }

    @Test
    public void tiesGoToTheFirstAddressAndKeepTheGivenOrder() {
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
        List<InetSocketAddress> addresses = List.of(A, B, C);

        assertEquals(addresses, select(selector, addresses, 0), "nothing to reorder when the first wins");
        assertEquals(1, selector.activeConnections(A));
        assertEquals(0, selector.activeConnections(B));
    }

    @Test
    public void picksTheAddressWithTheFewestConnectionsAndKeepsTheRestInOrder() {
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
        open(selector, A);
        open(selector, B);

        assertEquals(List.of(C, A, B), select(selector, List.of(A, B, C), 0));
        assertEquals(List.of(A, B, C), select(selector, List.of(A, B, C), 0), "all at one again, so A");
        assertEquals(List.of(B, A, C), select(selector, List.of(A, B, C), 0));
        assertEquals(2, selector.activeConnections(A));
        assertEquals(2, selector.activeConnections(B));
        assertEquals(1, selector.activeConnections(C));
    }

    @Test
    public void aFailoverSelectsAmongTheAddressesNotTriedYet() {
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
        open(selector, B);

        // A was tried and failed; B holds a connection, so the failover goes to C, and A keeps its place.
        assertEquals(List.of(A, C, B), select(selector, List.of(A, B, C), 1));
        assertEquals(0, selector.activeConnections(A), "an address already tried is never selected again");
        assertEquals(1, selector.activeConnections(C));

        open(selector, C);
        assertEquals(List.of(A, B, C), select(selector, List.of(A, B, C), 1), "tied at one, so B");
        assertEquals(List.of(A, B, C), select(selector, List.of(A, B, C), 2), "only C is left");
    }

    // The order in which one connection tries the addresses when every attempt fails.
    private static List<InetSocketAddress> failEveryAttempt(LeastConnectionsAddressSelector selector,
                                                            List<InetSocketAddress> answer) {
        List<InetSocketAddress> addresses = new ArrayList<>(answer);
        List<InetSocketAddress> tried = new ArrayList<>();
        for (int from = 0; from < addresses.size(); from++) {
            InetSocketAddress attempt = selector.selectAndAcquire(HOST, addresses, from);
            tried.add(attempt);
            selector.release(attempt);
        }
        return tried;
    }

    @Test
    public void aFailoverTriesTheFamilyWithFewerFailedAttemptsFirst() {
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
        open(selector, A);
        open(selector, B);

        // IPv6 has no connections and wins the first pick. Once it fails, IPv4 goes next despite its higher count.
        // With one failure each, the count decides. After a second IPv6 failure, IPv4 goes next again.
        assertEquals(List.of(V6, A, V6B, B, V6C), failEveryAttempt(selector, List.of(A, B, V6, V6B, V6C)),
                "the families alternate, and every address is tried once");
        assertEquals(1, selector.activeConnections(A));
        assertEquals(1, selector.activeConnections(B));
        assertEquals(0, selector.activeConnections(V6));

        // With IPv4 used up, the failed family is the only one left.
        assertEquals(List.of(V6, A, V6B), failEveryAttempt(selector, List.of(V6, V6B, A)));
    }

    /**
     * IPv6 is unreachable and the first IPv4 address is dead too. The live IPv4 address, which holds connections,
     * is reached after one more IPv6 attempt rather than after all of them.
     */
    @Test
    public void aDoubleFaultCostsAtMostOneExtraAttemptInTheUnreachableFamily() {
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
        open(selector, B);
        open(selector, B);

        List<InetSocketAddress> tried = failEveryAttempt(selector, List.of(A, V6, V6B, V6C, B));
        assertEquals(List.of(A, V6, V6B, B), tried.subList(0, 4));
    }

    @Test
    public void aCoolingAddressRanksAfterAFamilyThatFailed() {
        AtomicLong now = new AtomicLong();
        FailedIpCooldownHolder cooldown = new FailedIpCooldownHolder(SECONDS.toNanos(10), now::get);
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(cooldown);
        cooldown.reorder(HOST, List.of(V6, V6B, A));
        cooldown.markFailed(HOST, A);

        // V6 was tried and failed; A is of the other family but cooling, so the second IPv6 address goes first.
        assertEquals(List.of(V6, V6B, A), select(selector, List.of(V6, V6B, A), 1));
    }

    @Test
    public void aSingleFamilyFailoverIsRankedByCountAlone() {
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
        open(selector, V6B);
        assertEquals(List.of(V6, V6C, V6B), failEveryAttempt(selector, List.of(V6, V6B, V6C)));
        open(selector, B);
        assertEquals(List.of(A, C, B), failEveryAttempt(selector, List.of(A, B, C)));
    }

    @Test
    public void countsEverySelectionSoConcurrentPendingConnectsSpread() {
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
        List<InetSocketAddress> firsts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            firsts.add(first(selector, List.of(A, B, C)));
        }
        assertEquals(List.of(A, B, C, A, B, C), firsts);
    }

    @Test
    public void releaseDropsTheCountAndForgetsTheAddressAtZero() {
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
        open(selector, A);
        open(selector, A);
        open(selector, B);

        selector.release(A);
        assertEquals(1, selector.activeConnections(A));
        assertEquals(A, first(selector, List.of(A, B)), "tied at one, so A");
        selector.release(A);
        selector.release(A);
        assertEquals(0, selector.activeConnections(A));
        selector.release(A);
        assertEquals(0, selector.activeConnections(A), "an unmatched release must not go negative");
        assertEquals(A, first(selector, List.of(A, B)));
    }

    @Test
    public void aMixedFamilyAnswerKeepsTheResolverOrderForTiesAndOtherwiseGoesByCount() {
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
        assertEquals(A, first(selector, List.of(A, V6)), "IPv4 first in the answer, so IPv4 on the tie");
        assertEquals(V6, first(selector, List.of(A, V6)), "fewer connections wins across families");
        assertEquals(A, first(selector, List.of(A, V6)));

        LeastConnectionsAddressSelector v6First = new LeastConnectionsAddressSelector(null);
        assertEquals(V6, first(v6First, List.of(V6, A)), "IPv6 first in the answer, so IPv6 on the tie");
    }

    @Test
    public void aCoolingAddressRanksLastWhateverItsCount() {
        AtomicLong now = new AtomicLong();
        FailedIpCooldownHolder cooldown = new FailedIpCooldownHolder(SECONDS.toNanos(10), now::get);
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(cooldown);
        cooldown.reorder(HOST, List.of(A, B));
        cooldown.markFailed(HOST, A);
        open(selector, B);
        open(selector, B);

        // What NettyRequestSender hands over: the cooldown order, with the cooling A at the back.
        List<InetSocketAddress> ordered = cooldown.reorder(HOST, List.of(A, B));
        assertEquals(List.of(B, A), ordered);
        assertEquals(List.of(B, A), select(selector, ordered, 0),
                "A has no connections but is cooling, so B still goes first");
    }

    /**
     * When the cooldown of an address that keeps failing lapses, the address is a candidate again with no
     * connections. New connections target it first until its pending attempts catch up with the other address, or
     * one of them fails and restarts the cooldown. Documented on
     * {@link org.asynchttpclient.LoadBalance#LEAST_CONNECTIONS}.
     */
    @Test
    public void anAddressWhoseCooldownLapsedIsRetriedByNewConnectionsUntilOneFails() {
        AtomicLong now = new AtomicLong();
        FailedIpCooldownHolder cooldown = new FailedIpCooldownHolder(SECONDS.toNanos(10), now::get);
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(cooldown);
        cooldown.reorder(HOST, List.of(A, B));
        cooldown.markFailed(HOST, B);
        for (int i = 0; i < 3; i++) {
            assertEquals(A, first(selector, cooldown.reorder(HOST, List.of(A, B))), "B is cooling");
        }

        now.addAndGet(SECONDS.toNanos(11));
        List<InetSocketAddress> firsts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            firsts.add(first(selector, cooldown.reorder(HOST, List.of(A, B))));
        }
        assertEquals(List.of(B, B, B, A), firsts, "three attempts pending at B tie with the three connections at A");

        cooldown.markFailed(HOST, B);
        selector.release(B);
        assertEquals(A, first(selector, cooldown.reorder(HOST, List.of(A, B))), "one failure puts B back in cooldown");
    }

    @Test
    public void aCooldownThatLapsedIsNotHeldAgainstTheAddressWithoutAReorder() {
        AtomicLong now = new AtomicLong();
        FailedIpCooldownHolder cooldown = new FailedIpCooldownHolder(SECONDS.toNanos(10), now::get);
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(cooldown);
        cooldown.reorder(HOST, List.of(A, B));
        cooldown.markFailed(HOST, A);
        assertEquals(B, first(selector, List.of(A, B)), "A is cooling");

        // A failover can pick long after the reorder() that would have pruned the lapsed entry.
        now.addAndGet(SECONDS.toNanos(11));
        assertEquals(A, first(selector, List.of(A, B)), "tied at one with B, and no longer cooling");
    }

    @Test
    public void whenEveryAddressIsCoolingTheCountDecides() {
        AtomicLong now = new AtomicLong();
        FailedIpCooldownHolder cooldown = new FailedIpCooldownHolder(SECONDS.toNanos(10), now::get);
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(cooldown);
        cooldown.reorder(HOST, List.of(A, B));
        cooldown.markFailed(HOST, A);
        cooldown.markFailed(HOST, B);
        open(selector, A);

        assertEquals(List.of(B, A), select(selector, cooldown.reorder(HOST, List.of(A, B)), 0));
    }

    @Test
    public void onlyTheGivenAddressesAreCandidatesAndALeftAddressStillReleases() {
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
        first(selector, List.of(A, B));
        assertEquals(1, selector.activeConnections(A));

        // A has left the resolver's answer: it is never picked, however few connections it has.
        assertEquals(List.of(B, C), select(selector, List.of(B, C), 0));
        assertEquals(List.of(C, B), select(selector, List.of(B, C), 0));
        selector.release(A);
        assertEquals(0, selector.activeConnections(A), "its live connection still releases after it left");
    }

    @Test
    public void concurrentSelectionsSpreadEvenly() throws Exception {
        LeastConnectionsAddressSelector selector = new LeastConnectionsAddressSelector(null);
        int threads = 6;
        int perThread = 500;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                done.add(pool.submit(() -> {
                    start.await(10, SECONDS);
                    for (int i = 0; i < perThread; i++) {
                        first(selector, List.of(A, B, C));
                    }
                    return null;
                }));
            }
            for (Future<?> f : done) {
                f.get(30, SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        int each = threads * perThread / 3;
        assertEquals(each, selector.activeConnections(A));
        assertEquals(each, selector.activeConnections(B));
        assertEquals(each, selector.activeConnections(C));
    }
}
