/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.testng.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.testng.annotations.Test;

/**
 * Created by grenville on 9/25/16.
 */
public class ClientStatsTest extends AbstractBasicTest {

    @Test(groups = "standalone")
    public void testClientStatus() throws Throwable {
        try (final AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(true).setPooledConnectionIdleTimeout(5000))) {
            final String url = getTargetUrl();

            final ClientStats emptyStats = client.getClientStats();

            assertEquals(emptyStats.toString(), "There are 0 total connections, 0 are active and 0 are idle.");
            assertEquals(emptyStats.getActiveConnectionCount(), 0);
            assertEquals(emptyStats.getIdleConnectionCount(), 0);
            assertEquals(emptyStats.getTotalConnectionCount(), 0);

            final List<ListenableFuture<Response>> futures =
                    Stream.generate(() -> client.prepareGet(url).setHeader("LockThread","6").execute())
                            .limit(5)
                            .collect(Collectors.toList());

            Thread.sleep(2000);

            final ClientStats activeStats = client.getClientStats();

            assertEquals(activeStats.toString(), "There are 5 total connections, 5 are active and 0 are idle.");
            assertEquals(activeStats.getActiveConnectionCount(), 5);
            assertEquals(activeStats.getIdleConnectionCount(), 0);
            assertEquals(activeStats.getTotalConnectionCount(), 5);

            futures.forEach(future -> future.toCompletableFuture().join());

            Thread.sleep(1000);

            final ClientStats idleStats = client.getClientStats();

            assertEquals(idleStats.toString(), "There are 5 total connections, 0 are active and 5 are idle.");
            assertEquals(idleStats.getActiveConnectionCount(), 0);
            assertEquals(idleStats.getIdleConnectionCount(), 5);
            assertEquals(idleStats.getTotalConnectionCount(), 5);

            // Let's make sure the active count is correct when reusing cached connections.

            final List<ListenableFuture<Response>> repeatedFutures =
                    Stream.generate(() -> client.prepareGet(url).setHeader("LockThread","6").execute())
                            .limit(3)
                            .collect(Collectors.toList());

            Thread.sleep(2000);

            final ClientStats activeCachedStats = client.getClientStats();

            assertEquals(activeCachedStats.toString(), "There are 5 total connections, 3 are active and 2 are idle.");
            assertEquals(activeCachedStats.getActiveConnectionCount(), 3);
            assertEquals(activeCachedStats.getIdleConnectionCount(), 2);
            assertEquals(activeCachedStats.getTotalConnectionCount(), 5);

            repeatedFutures.forEach(future -> future.toCompletableFuture().join());

            Thread.sleep(1000);

            final ClientStats idleCachedStats = client.getClientStats();

            assertEquals(idleCachedStats.toString(), "There are 3 total connections, 0 are active and 3 are idle.");
            assertEquals(idleCachedStats.getActiveConnectionCount(), 0);
            assertEquals(idleCachedStats.getIdleConnectionCount(), 3);
            assertEquals(idleCachedStats.getTotalConnectionCount(), 3);

            Thread.sleep(5000);

            final ClientStats timeoutStats = client.getClientStats();

            assertEquals(timeoutStats.toString(), "There are 0 total connections, 0 are active and 0 are idle.");
            assertEquals(timeoutStats.getActiveConnectionCount(), 0);
            assertEquals(timeoutStats.getIdleConnectionCount(), 0);
            assertEquals(timeoutStats.getTotalConnectionCount(), 0);
        }
    }

    @Test(groups = "standalone")
    public void testClientStatusNoKeepalive() throws Throwable {
        try (final AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(false))) {
            final String url = getTargetUrl();

            final ClientStats emptyStats = client.getClientStats();

            assertEquals(emptyStats.toString(), "There are 0 total connections, 0 are active and 0 are idle.");
            assertEquals(emptyStats.getActiveConnectionCount(), 0);
            assertEquals(emptyStats.getIdleConnectionCount(), 0);
            assertEquals(emptyStats.getTotalConnectionCount(), 0);

            final List<ListenableFuture<Response>> futures =
                    Stream.generate(() -> client.prepareGet(url).setHeader("LockThread","6").execute())
                            .limit(5)
                            .collect(Collectors.toList());

            Thread.sleep(2000);

            final ClientStats activeStats = client.getClientStats();

            assertEquals(activeStats.toString(), "There are 5 total connections, 5 are active and 0 are idle.");
            assertEquals(activeStats.getActiveConnectionCount(), 5);
            assertEquals(activeStats.getIdleConnectionCount(), 0);
            assertEquals(activeStats.getTotalConnectionCount(), 5);

            futures.forEach(future -> future.toCompletableFuture().join());

            Thread.sleep(1000);

            final ClientStats idleStats = client.getClientStats();

            assertEquals(idleStats.toString(), "There are 0 total connections, 0 are active and 0 are idle.");
            assertEquals(idleStats.getActiveConnectionCount(), 0);
            assertEquals(idleStats.getIdleConnectionCount(), 0);
            assertEquals(idleStats.getTotalConnectionCount(), 0);

            // Let's make sure the active count is correct when reusing cached connections.

            final List<ListenableFuture<Response>> repeatedFutures =
                    Stream.generate(() -> client.prepareGet(url).setHeader("LockThread","6").execute())
                            .limit(3)
                            .collect(Collectors.toList());

            Thread.sleep(2000);

            final ClientStats activeCachedStats = client.getClientStats();

            assertEquals(activeCachedStats.toString(), "There are 3 total connections, 3 are active and 0 are idle.");
            assertEquals(activeCachedStats.getActiveConnectionCount(), 3);
            assertEquals(activeCachedStats.getIdleConnectionCount(), 0);
            assertEquals(activeCachedStats.getTotalConnectionCount(), 3);

            repeatedFutures.forEach(future -> future.toCompletableFuture().join());

            Thread.sleep(1000);

            final ClientStats idleCachedStats = client.getClientStats();

            assertEquals(idleCachedStats.toString(), "There are 0 total connections, 0 are active and 0 are idle.");
            assertEquals(idleCachedStats.getActiveConnectionCount(), 0);
            assertEquals(idleCachedStats.getIdleConnectionCount(), 0);
            assertEquals(idleCachedStats.getTotalConnectionCount(), 0);
        }
    }
}
