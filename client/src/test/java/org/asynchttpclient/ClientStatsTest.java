package org.asynchttpclient;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.testng.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Created by grenville on 9/25/16.
 */
public class ClientStatsTest extends AbstractBasicTest {

    @Test(groups = "standalone")
    public void testClientStatus() throws Throwable {
        try (final DefaultAsyncHttpClient client = (DefaultAsyncHttpClient) asyncHttpClient(config().setKeepAlive(true).setPooledConnectionIdleTimeout(5000))) {
            final String url = getTargetUrl();

            final ClientStats emptyStats = client.getClientStats();

            assertEquals(emptyStats.toString(), "There are 0 total connections, 0 are active and 0 are idle.");
            assertEquals(emptyStats.getActiveConnectionCount(), 0);
            assertEquals(emptyStats.getIdleConnectionCount(), 0);
            assertEquals(emptyStats.getTotalConnectionCount(), 0);

            final List<ListenableFuture<Response>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                logger.info("{} requesting url [{}]...", i, url);
                futures.add(client.prepareGet(url).setHeader("LockThread", "6").execute());
            }

            Thread.sleep(2000);

            final ClientStats activeStats = client.getClientStats();

            assertEquals(activeStats.toString(), "There are 5 total connections, 5 are active and 0 are idle.");
            assertEquals(activeStats.getActiveConnectionCount(), 5);
            assertEquals(activeStats.getIdleConnectionCount(), 0);
            assertEquals(activeStats.getTotalConnectionCount(), 5);

            for (final ListenableFuture<Response> future : futures) {
                future.get();
            }

            Thread.sleep(1000);

            final ClientStats idleStats = client.getClientStats();

            assertEquals(idleStats.toString(), "There are 5 total connections, 0 are active and 5 are idle.");
            assertEquals(idleStats.getActiveConnectionCount(), 0);
            assertEquals(idleStats.getIdleConnectionCount(), 5);
            assertEquals(idleStats.getTotalConnectionCount(), 5);

            // Let's make sure the active count is correct when reusing cached connections.

            final List<ListenableFuture<Response>> repeatedFutures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                logger.info("{} requesting url [{}]...", i, url);
                repeatedFutures.add(client.prepareGet(url).setHeader("LockThread", "6").execute());
            }

            Thread.sleep(2000);

            final ClientStats activeCachedStats = client.getClientStats();

            assertEquals(activeCachedStats.toString(), "There are 5 total connections, 3 are active and 2 are idle.");
            assertEquals(activeCachedStats.getActiveConnectionCount(), 3);
            assertEquals(activeCachedStats.getIdleConnectionCount(), 2);
            assertEquals(activeCachedStats.getTotalConnectionCount(), 5);

            for (final ListenableFuture<Response> future : repeatedFutures) {
                future.get();
            }

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
        try (final DefaultAsyncHttpClient client = (DefaultAsyncHttpClient) asyncHttpClient(config().setKeepAlive(false))) {
            final String url = getTargetUrl();

            final ClientStats emptyStats = client.getClientStats();

            assertEquals(emptyStats.toString(), "There are 0 total connections, 0 are active and 0 are idle.");
            assertEquals(emptyStats.getActiveConnectionCount(), 0);
            assertEquals(emptyStats.getIdleConnectionCount(), 0);
            assertEquals(emptyStats.getTotalConnectionCount(), 0);

            final List<ListenableFuture<Response>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                logger.info("{} requesting url [{}]...", i, url);
                futures.add(client.prepareGet(url).setHeader("LockThread", "6").execute());
            }

            Thread.sleep(2000);

            final ClientStats activeStats = client.getClientStats();

            assertEquals(activeStats.toString(), "There are 5 total connections, 5 are active and 0 are idle.");
            assertEquals(activeStats.getActiveConnectionCount(), 5);
            assertEquals(activeStats.getIdleConnectionCount(), 0);
            assertEquals(activeStats.getTotalConnectionCount(), 5);

            for (final ListenableFuture<Response> future : futures) {
                future.get();
            }

            Thread.sleep(1000);

            final ClientStats idleStats = client.getClientStats();

            assertEquals(idleStats.toString(), "There are 0 total connections, 0 are active and 0 are idle.");
            assertEquals(idleStats.getActiveConnectionCount(), 0);
            assertEquals(idleStats.getIdleConnectionCount(), 0);
            assertEquals(idleStats.getTotalConnectionCount(), 0);

            // Let's make sure the active count is correct when reusing cached connections.

            final List<ListenableFuture<Response>> repeatedFutures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                logger.info("{} requesting url [{}]...", i, url);
                repeatedFutures.add(client.prepareGet(url).setHeader("LockThread", "6").execute());
            }

            Thread.sleep(2000);

            final ClientStats activeCachedStats = client.getClientStats();

            assertEquals(activeCachedStats.toString(), "There are 3 total connections, 3 are active and 0 are idle.");
            assertEquals(activeCachedStats.getActiveConnectionCount(), 3);
            assertEquals(activeCachedStats.getIdleConnectionCount(), 0);
            assertEquals(activeCachedStats.getTotalConnectionCount(), 3);

            for (final ListenableFuture<Response> future : repeatedFutures) {
                future.get();
            }

            Thread.sleep(1000);

            final ClientStats idleCachedStats = client.getClientStats();

            assertEquals(idleCachedStats.toString(), "There are 0 total connections, 0 are active and 0 are idle.");
            assertEquals(idleCachedStats.getActiveConnectionCount(), 0);
            assertEquals(idleCachedStats.getIdleConnectionCount(), 0);
            assertEquals(idleCachedStats.getTotalConnectionCount(), 0);
        }
    }
}
