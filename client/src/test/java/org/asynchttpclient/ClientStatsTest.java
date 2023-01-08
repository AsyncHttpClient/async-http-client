/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

import io.github.artsok.RepeatedIfExceptionsTest;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Created by grenville on 9/25/16.
 */
public class ClientStatsTest extends AbstractBasicTest {

    private static final String hostname = "localhost";

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testClientStatus() throws Throwable {
        try (final AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(true).setPooledConnectionIdleTimeout(5000))) {
            final String url = getTargetUrl();

            final ClientStats emptyStats = client.getClientStats();

            assertEquals("There are 0 total connections, 0 are active and 0 are idle.", emptyStats.toString());
            assertEquals(0, emptyStats.getTotalActiveConnectionCount());
            assertEquals(0, emptyStats.getTotalIdleConnectionCount());
            assertEquals(0, emptyStats.getTotalConnectionCount());
            assertNull(emptyStats.getStatsPerHost().get(hostname));

            final List<ListenableFuture<Response>> futures = Stream.generate(() -> client.prepareGet(url).setHeader("LockThread", "6").execute())
                    .limit(5)
                    .collect(Collectors.toList());

            Thread.sleep(2000 + 1000);

            final ClientStats activeStats = client.getClientStats();

            assertEquals("There are 5 total connections, 5 are active and 0 are idle.", activeStats.toString());
            assertEquals(5, activeStats.getTotalActiveConnectionCount());
            assertEquals(0, activeStats.getTotalIdleConnectionCount());
            assertEquals(5, activeStats.getTotalConnectionCount());
            assertEquals(5, activeStats.getStatsPerHost().get(hostname).getHostConnectionCount());

            futures.forEach(future -> future.toCompletableFuture().join());

            Thread.sleep(1000 + 1000);

            final ClientStats idleStats = client.getClientStats();

            assertEquals("There are 5 total connections, 0 are active and 5 are idle.", idleStats.toString());
            assertEquals(0, idleStats.getTotalActiveConnectionCount());
            assertEquals(5, idleStats.getTotalIdleConnectionCount());
            assertEquals(5, idleStats.getTotalConnectionCount());
            assertEquals(5, idleStats.getStatsPerHost().get(hostname).getHostConnectionCount());

            // Let's make sure the active count is correct when reusing cached connections.

            final List<ListenableFuture<Response>> repeatedFutures = Stream.generate(() -> client.prepareGet(url).setHeader("LockThread", "6").execute())
                    .limit(3)
                    .collect(Collectors.toList());

            Thread.sleep(2000 + 1000);

            final ClientStats activeCachedStats = client.getClientStats();

            assertEquals("There are 5 total connections, 3 are active and 2 are idle.", activeCachedStats.toString());
            assertEquals(3, activeCachedStats.getTotalActiveConnectionCount());
            assertEquals(2, activeCachedStats.getTotalIdleConnectionCount());
            assertEquals(5, activeCachedStats.getTotalConnectionCount());
            assertEquals(5, activeCachedStats.getStatsPerHost().get(hostname).getHostConnectionCount());

            repeatedFutures.forEach(future -> future.toCompletableFuture().join());

            Thread.sleep(1000 + 1000);

            final ClientStats idleCachedStats = client.getClientStats();

            assertEquals("There are 3 total connections, 0 are active and 3 are idle.", idleCachedStats.toString());
            assertEquals(0, idleCachedStats.getTotalActiveConnectionCount());
            assertEquals(3, idleCachedStats.getTotalIdleConnectionCount());
            assertEquals(3, idleCachedStats.getTotalConnectionCount());
            assertEquals(3, idleCachedStats.getStatsPerHost().get(hostname).getHostConnectionCount());

            Thread.sleep(5000 + 1000);

            final ClientStats timeoutStats = client.getClientStats();

            assertEquals("There are 0 total connections, 0 are active and 0 are idle.", timeoutStats.toString());
            assertEquals(0, timeoutStats.getTotalActiveConnectionCount());
            assertEquals(0, timeoutStats.getTotalIdleConnectionCount());
            assertEquals(0, timeoutStats.getTotalConnectionCount());
            assertNull(timeoutStats.getStatsPerHost().get(hostname));
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testClientStatusNoKeepalive() throws Throwable {
        try (final AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(false).setPooledConnectionIdleTimeout(1000))) {
            final String url = getTargetUrl();

            final ClientStats emptyStats = client.getClientStats();

            assertEquals("There are 0 total connections, 0 are active and 0 are idle.", emptyStats.toString());
            assertEquals(0, emptyStats.getTotalActiveConnectionCount());
            assertEquals(0, emptyStats.getTotalIdleConnectionCount());
            assertEquals(0, emptyStats.getTotalConnectionCount());
            assertNull(emptyStats.getStatsPerHost().get(hostname));

            final List<ListenableFuture<Response>> futures = Stream.generate(() -> client.prepareGet(url).setHeader("LockThread", "6").execute())
                    .limit(5)
                    .collect(Collectors.toList());

            Thread.sleep(2000 + 1000);

            final ClientStats activeStats = client.getClientStats();

            assertEquals("There are 5 total connections, 5 are active and 0 are idle.", activeStats.toString());
            assertEquals(5, activeStats.getTotalActiveConnectionCount());
            assertEquals(0, activeStats.getTotalIdleConnectionCount());
            assertEquals(5, activeStats.getTotalConnectionCount());
            assertEquals(5, activeStats.getStatsPerHost().get(hostname).getHostConnectionCount());

            futures.forEach(future -> future.toCompletableFuture().join());

            Thread.sleep(1000 + 1000);

            final ClientStats idleStats = client.getClientStats();

            assertEquals("There are 0 total connections, 0 are active and 0 are idle.", idleStats.toString());
            assertEquals(0, idleStats.getTotalActiveConnectionCount());
            assertEquals(0, idleStats.getTotalIdleConnectionCount());
            assertEquals(0, idleStats.getTotalConnectionCount());
            assertNull(idleStats.getStatsPerHost().get(hostname));

            // Let's make sure the active count is correct when reusing cached connections.

            final List<ListenableFuture<Response>> repeatedFutures = Stream.generate(() -> client.prepareGet(url).setHeader("LockThread", "6").execute())
                    .limit(3)
                    .collect(Collectors.toList());

            Thread.sleep(2000 + 1000);

            final ClientStats activeCachedStats = client.getClientStats();

            assertEquals("There are 3 total connections, 3 are active and 0 are idle.", activeCachedStats.toString());
            assertEquals(3, activeCachedStats.getTotalActiveConnectionCount());
            assertEquals(0, activeCachedStats.getTotalIdleConnectionCount());
            assertEquals(3, activeCachedStats.getTotalConnectionCount());
            assertEquals(3, activeCachedStats.getStatsPerHost().get(hostname).getHostConnectionCount());

            repeatedFutures.forEach(future -> future.toCompletableFuture().join());

            Thread.sleep(1000 + 1000);

            final ClientStats idleCachedStats = client.getClientStats();

            assertEquals("There are 0 total connections, 0 are active and 0 are idle.", idleCachedStats.toString());
            assertEquals(0, idleCachedStats.getTotalActiveConnectionCount());
            assertEquals(0, idleCachedStats.getTotalIdleConnectionCount());
            assertEquals(0, idleCachedStats.getTotalConnectionCount());
            assertNull(idleCachedStats.getStatsPerHost().get(hostname));
        }
    }
}
