/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Test;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.test.EventCollectingHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.function.ThrowingSupplier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.test.EventCollectingHandler.COMPLETED_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.CONNECTION_OFFER_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.CONNECTION_POOLED_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.CONNECTION_POOL_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.HEADERS_RECEIVED_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.HEADERS_WRITTEN_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.REQUEST_SEND_EVENT;
import static org.asynchttpclient.test.EventCollectingHandler.STATUS_RECEIVED_EVENT;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class ConnectionPoolTest extends AbstractBasicTest {

    @Test
    public void testMaxTotalConnections() throws Exception {
        registerRequest();

        try (AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(true).setMaxConnections(1))) {
            String url = getTargetUrl();

            for (int i = 0; i < 3; i++) {
                logger.info("{} requesting url [{}]...", i, url);

                Response response = assertDoesNotThrow(new ThrowingSupplier<Response>() {
                    @Override
                    public Response get() throws Throwable {
                        return client.prepareGet(url).execute().get();
                    }
                });

                assertNotNull(response);
                logger.info("{} response [{}].", i, response);
            }
        } finally {
            deregisterRequest();
        }
    }

    @Test
    public void testMaxTotalConnectionsException() throws Exception {
        registerRequest();

        try (AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(true).setMaxConnections(1))) {
            String url = getTargetUrl();

            List<ListenableFuture<Response>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                logger.info("{} requesting url [{}]...", i, url);
                futures.add(client.prepareGet(url).execute());
            }

            Exception exception = null;
            for (ListenableFuture<Response> future : futures) {
                try {
                    future.get();
                } catch (Exception ex) {
                    exception = ex;
                    break;
                }
            }

            assertNotNull(exception);
            assertInstanceOf(ExecutionException.class, exception);
        } finally {
            deregisterRequest();
        }
    }

    @Test
    public void asyncDoGetKeepAliveHandlerTest_channelClosedDoesNotFail() throws Exception {
        for (int i = 0; i < 10; i++) {
            registerRequest();

            try (AsyncHttpClient client = asyncHttpClient()) {
                final CountDownLatch l = new CountDownLatch(2);
                final Map<String, Boolean> remoteAddresses = new ConcurrentHashMap<>();

                AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

                    @Override
                    public Response onCompleted(Response response) {
                        logger.debug("ON COMPLETED INVOKED " + response.getHeader("X-KEEP-ALIVE"));
                        try {
                            assertEquals(200, response.getStatusCode());
                            remoteAddresses.put(response.getHeader("X-KEEP-ALIVE"), true);
                        } finally {
                            l.countDown();
                        }
                        return response;
                    }

                    @Override
                    public void onThrowable(Throwable t) {
                        try {
                            super.onThrowable(t);
                        } finally {
                            l.countDown();
                        }
                    }
                };

                client.prepareGet(getTargetUrl()).execute(handler).get();
                server.stop();

                // Jetty 9.4.8 doesn't properly stop and restart (recreates ReservedThreadExecutors on start but still point to old offers threads to old ones)
                // instead of restarting, we create a fresh new one and have it bind on the same port
                server = new Server();
                ServerConnector newConnector = addHttpConnector(server);
                // make sure connector will restart with the port as it's originally dynamically allocated
                newConnector.setPort(port1);
                server.setHandler(configureHandler());
                server.start();

                client.prepareGet(getTargetUrl()).execute(handler);

                if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                    fail("Timed out");
                }

                assertEquals(remoteAddresses.size(), 2);
            } finally {
                deregisterRequest();
            }
        }
    }

    @Test
    public void multipleMaxConnectionOpenTest() throws Exception {
        registerRequest();

        try (AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(true).setConnectTimeout(5000).setMaxConnections(1))) {
            String body = "hello there";

            // once
            Response response = client.preparePost(getTargetUrl()).setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), body);

            // twice
            assertThrows(ExecutionException.class, () -> client.preparePost(String.format("http://localhost:%d/foo/test", port2))
                    .setBody(body)
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS));
        } finally {
            deregisterRequest();
        }
    }

    @Test
    public void multipleMaxConnectionOpenTestWithQuery() throws Exception {
        registerRequest();

        try (AsyncHttpClient c = asyncHttpClient(config().setKeepAlive(true).setConnectTimeout(5000).setMaxConnections(1))) {
            String body = "hello there";

            // once
            Response response = c.preparePost(getTargetUrl() + "?foo=bar").setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), "foo_" + body);

            // twice
            response = c.preparePost(getTargetUrl()).setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
        } finally {
            deregisterRequest();
        }
    }

    @Test
    public void asyncHandlerOnThrowableTest() throws Exception {
        registerRequest();

        try (AsyncHttpClient client = asyncHttpClient()) {
            final AtomicInteger count = new AtomicInteger();
            final String THIS_IS_NOT_FOR_YOU = "This is not for you";
            final CountDownLatch latch = new CountDownLatch(16);
            for (int i = 0; i < 16; i++) {
                client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerBase() {
                    @Override
                    public Response onCompleted(Response response) throws Exception {
                        throw new Exception(THIS_IS_NOT_FOR_YOU);
                    }
                });

                client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerBase() {
                    @Override
                    public void onThrowable(Throwable t) {
                        if (t.getMessage() != null && t.getMessage().equalsIgnoreCase(THIS_IS_NOT_FOR_YOU)) {
                            count.incrementAndGet();
                        }
                    }

                    @Override
                    public Response onCompleted(Response response) {
                        latch.countDown();
                        return response;
                    }
                });
            }
            latch.await(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(count.get(), 0);
        } finally {
            deregisterRequest();
        }
    }

    @Test
    public void nonPoolableConnectionReleaseSemaphoresTest() throws Throwable {
        registerRequest();

        RequestBuilder request = get(getTargetUrl()).setHeader("Connection", "close");

        try (AsyncHttpClient client = asyncHttpClient(config().setMaxConnections(6).setMaxConnectionsPerHost(3))) {
            client.executeRequest(request).get();
            Thread.sleep(1000);
            client.executeRequest(request).get();
            Thread.sleep(1000);
            client.executeRequest(request).get();
            Thread.sleep(1000);
            client.executeRequest(request).get();
        } finally {
            deregisterRequest();
        }
    }

    @Test
    public void testPooledEventsFired() throws Exception {
        registerRequest();

        RequestBuilder request = get("http://localhost:" + port1 + "/Test");

        try (AsyncHttpClient client = asyncHttpClient()) {
            EventCollectingHandler firstHandler = new EventCollectingHandler();
            client.executeRequest(request, firstHandler).get(3, TimeUnit.SECONDS);
            firstHandler.waitForCompletion(3, TimeUnit.SECONDS);

            EventCollectingHandler secondHandler = new EventCollectingHandler();
            client.executeRequest(request, secondHandler).get(3, TimeUnit.SECONDS);
            secondHandler.waitForCompletion(3, TimeUnit.SECONDS);

            Object[] expectedEvents = {CONNECTION_POOL_EVENT, CONNECTION_POOLED_EVENT, REQUEST_SEND_EVENT, HEADERS_WRITTEN_EVENT, STATUS_RECEIVED_EVENT,
                    HEADERS_RECEIVED_EVENT, CONNECTION_OFFER_EVENT, COMPLETED_EVENT};

            assertArrayEquals(secondHandler.firedEvents.toArray(), expectedEvents, "Got " + Arrays.toString(secondHandler.firedEvents.toArray()));
        } finally {
            deregisterRequest();
        }
    }
}
