/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
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
package com.ning.http.client.async;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ConnectionsPool;
import com.ning.http.client.Response;
import com.ning.http.client.logging.LogManager;
import com.ning.http.client.logging.Logger;
import org.jboss.netty.channel.Channel;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class ConnectionPoolTest extends AbstractBasicTest {
    protected final Logger log = LogManager.getLogger(AbstractBasicTest.class);

    @Test(groups = "standalone")
    public void testMaxTotalConnections() {
        AsyncHttpClient client = new AsyncHttpClient(
                new AsyncHttpClientConfig.Builder()
                        .setConnectionTimeoutInMs(100)
                        .setRequestTimeoutInMs(100)
                        .setKeepAlive(true)
                        .setMaximumConnectionsTotal(1)
                        .build()
        );

        String url = getTargetUrl();
        int i;
        for (i = 0; i < 3; i++) {
            try {
                log.info(String.format("%d requesting url [%s]...", i, url));
                Response response = client.prepareGet(url).execute().get();
                log.info(String.format("%d response [%s].", i, response));
            } catch (Exception e) {
                fail("ConnectionsCache Broken");
            }
        }
    }

    @Test(groups = {"standalone", "async"}, enabled = true, invocationCount = 10, alwaysRun = true)
    public void asyncDoGetKeepAliveHandlerTest_channelClosedDoesNotFail() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient();

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(2);

        final Map<String, Boolean> remoteAddresses = new
                ConcurrentHashMap<String, Boolean>();

        AsyncCompletionHandler<Response> handler = new
                AsyncCompletionHandlerAdapter() {

                    @Override
                    public Response onCompleted(Response response) throws
                            Exception {
                        System.out.println("ON COMPLETED INVOKED " +
                                response.getHeader("X-KEEP-ALIVE"));
                        try {
                            assertEquals(response.getStatusCode(), 200);
                            remoteAddresses.put(response.getHeader("X-KEEP-ALIVE"), true);
                        } finally {
                            l.countDown();
                        }
                        return response;
                    }
                };

        client.prepareGet(getTargetUrl()).execute(handler).get();
        server.stop();
        server.start();
        client.prepareGet(getTargetUrl()).execute(handler);

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }

        assertEquals(remoteAddresses.size(), 2);
    }

    @Test(groups = "standalone")
    public void testInvalidConnectionsPool() {

        ConnectionsPool<String, Channel> cp = new ConnectionsPool<String, Channel>() {

            public boolean addConnection(String key, Channel connection) {
                return false;
            }

            public Channel getConnection(String key) {
                return null;
            }

            public Channel removeConnection(String connection) {
                return null;
            }

            public boolean removeAllConnections(Channel connection) {
                return false;
            }

            public boolean canCacheConnection() {
                return false;
            }

            public void destroy() {

            }
        };

        AsyncHttpClient client = new AsyncHttpClient(
                new AsyncHttpClientConfig.Builder()
                        .setConnectionsPool(cp)
                        .build()
        );

        Exception exception = null;
        try {
            client.prepareGet(getTargetUrl()).execute().get(TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception ex) {
            ex.printStackTrace();
            exception = ex;
        }
        assertNotNull(exception);
        assertEquals(exception.getMessage(), "Too many connections -1");
    }

    @Test(groups = "standalone")
    public void testValidConnectionsPool() {

        ConnectionsPool<String, Channel> cp = new ConnectionsPool<String, Channel>() {

            public boolean addConnection(String key, Channel connection) {
                return true;
            }

            public Channel getConnection(String key) {
                return null;
            }

            public Channel removeConnection(String connection) {
                return null;
            }

            public boolean removeAllConnections(Channel connection) {
                return false;
            }

            public boolean canCacheConnection() {
                return true;
            }

            public void destroy() {

            }
        };

        AsyncHttpClient client = new AsyncHttpClient(
                new AsyncHttpClientConfig.Builder()
                        .setConnectionsPool(cp)
                        .build()
        );

        Exception exception = null;
        try {
            client.prepareGet(getTargetUrl()).execute().get(TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception ex) {
            ex.printStackTrace();
            exception = ex;
        }
        assertNull(exception);
    }


    @Test(groups = "standalone")
    public void multipleMaxConnectionOpenTest() throws Throwable {
        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setKeepAlive(true)
                .setConnectionTimeoutInMs(5000).setMaximumConnectionsTotal(1).build();
        AsyncHttpClient c = new AsyncHttpClient(cg);

        String body = "hello there";

        // once
        Response response = c.preparePost(getTargetUrl())
                .setBody(body)
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(response.getResponseBody(), body);

        // twice
        Exception exception = null;
        try {
            c.preparePost(String.format("http://127.0.0.1:%d/foo/test", port2)).setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);
            fail("Should throw exception. Too many connections issued.");
        } catch (Exception ex) {
            ex.printStackTrace();
            exception = ex;
        }
        assertNotNull(exception);
        assertEquals(exception.getMessage(), "Too many connections 1");
    }

    @Test(groups = "standalone")
    public void multipleMaxConnectionOpenTestWithQuery() throws Throwable {
        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setKeepAlive(true)
                .setConnectionTimeoutInMs(5000).setMaximumConnectionsTotal(1).build();
        AsyncHttpClient c = new AsyncHttpClient(cg);

        String body = "hello there";

        // once
        Response response = c.preparePost(getTargetUrl() + "?foo=bar")
                .setBody(body)
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(response.getResponseBody(), "foo_" + body);

        // twice
        Exception exception = null;
        try {
            response = c.preparePost(getTargetUrl()).setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception ex) {
            fail("Should throw exception. Too many connections issued.");

        }
        assertNotNull(response);
        assertEquals(response.getStatusCode(), 200);
    }

    @Test(groups = {"online", "async"})
    public void asyncDoGetMaxConnectionsTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setMaximumConnectionsTotal(2).build());

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(2);

        AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                l.countDown();
                return response;
            }

            @Override
            public void onThrowable(Throwable t) {
                try {
                    Assert.fail("Unexpected exception", t);
                } finally {
                    l.countDown();
                }
            }
        };

        client.prepareGet("http://www.oracle.com/index.html").execute(handler).get();
        client.prepareGet("http://www.apache.org/").execute(handler).get();

        try {
            client.prepareGet("http://www.ning.com/").execute(handler).get();
            Assert.fail();
        } catch (IOException ex) {
            String s = ex.getMessage();
            assertEquals(s, "Too many connections 2");
        }

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
        client.close();
    }

    @Test(groups = "standalone")
    public void win7DisconnectTest() throws Throwable {
        final AtomicBoolean isThrown = new AtomicBoolean(false);

        AsyncHttpClient client = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        AsyncCompletionHandler<Response> handler = new
                AsyncCompletionHandlerAdapter() {

                    @Override
                    public Response onCompleted(Response response) throws
                            Exception {

                        if (!isThrown.getAndSet(true)) {
                            StackTraceElement e = new StackTraceElement("sun.nio.ch.SocketDispatcher", "read0", null, -1);
                            IOException t = new IOException();
                            t.setStackTrace(new StackTraceElement[]{e});
                            throw t;
                        }
                        return response;
                    }
                };

        client.prepareGet(getTargetUrl()).execute(handler).get();
        Response response = client.prepareGet(getTargetUrl()).execute(handler).get();
        assertNotNull(response);
        assertEquals(response.getStatusCode(), 200);
        assertTrue(isThrown.get());
    }

}
