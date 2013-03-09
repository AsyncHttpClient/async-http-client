/*
 * Copyright (c) 2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package com.ning.http.client.async.grizzly;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.glassfish.grizzly.Connection;
import org.testng.annotations.Test;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ConnectionsPool;
import com.ning.http.client.Response;
import com.ning.http.client.async.ConnectionPoolTest;
import com.ning.http.client.async.ProviderUtil;

public class GrizzlyConnectionPoolTest extends ConnectionPoolTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return ProviderUtil.grizzlyProvider(config);
    }

    @Override
    @Test
    public void testMaxTotalConnectionsException() {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setAllowPoolingConnection(true).setMaximumConnectionsTotal(1).build());
        try {
            String url = getTargetUrl();
            int i;
            Exception exception = null;
            for (i = 0; i < 20; i++) {
                try {
                    log.info("{} requesting url [{}]...", i, url);

                    if (i < 5) {
                        client.prepareGet(url).execute().get();
                    } else {
                        client.prepareGet(url).execute();
                    }
                } catch (Exception ex) {
                    exception = ex;
                    break;
                }
            }
            assertNotNull(exception);
            assertNotNull(exception.getMessage());
        } finally {
            client.close();
        }
    }

    @Override
    public void testValidConnectionsPool() {
        ConnectionsPool<String, Connection> cp = new ConnectionsPool<String, Connection>() {

            public boolean offer(String key, Connection connection) {
                return true;
            }

            public Connection poll(String connection) {
                return null;
            }

            public boolean removeAll(Connection connection) {
                return false;
            }

            public boolean canCacheConnection() {
                return true;
            }

            public void destroy() {

            }
        };

        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setConnectionsPool(cp).build());
        try {
            Exception exception = null;
            try {
                client.prepareGet(getTargetUrl()).execute().get(TIMEOUT, TimeUnit.SECONDS);
            } catch (Exception ex) {
                ex.printStackTrace();
                exception = ex;
            }
            assertNull(exception);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testInvalidConnectionsPool() {

        ConnectionsPool<String, Connection> cp = new ConnectionsPool<String, Connection>() {

            public boolean offer(String key, Connection connection) {
                return false;
            }

            public Connection poll(String connection) {
                return null;
            }

            public boolean removeAll(Connection connection) {
                return false;
            }

            public boolean canCacheConnection() {
                return false;
            }

            public void destroy() {

            }
        };

        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setConnectionsPool(cp).build());
        try {
            Exception exception = null;
            try {
                client.prepareGet(getTargetUrl()).execute().get(TIMEOUT, TimeUnit.SECONDS);
            } catch (Exception ex) {
                ex.printStackTrace();
                exception = ex;
            }
            assertNotNull(exception);
        } finally {
            client.close();
        }
    }

    @Override
    @Test
    public void multipleMaxConnectionOpenTest() throws Throwable {
        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setAllowPoolingConnection(true).setConnectionTimeoutInMs(5000).setMaximumConnectionsTotal(1).build();
        AsyncHttpClient c = getAsyncHttpClient(cg);
        try {
            String body = "hello there";

            // once
            Response response = c.preparePost(getTargetUrl()).setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);

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
        } finally {
            c.close();
        }
    }

    @Override
    @Test
    public void win7DisconnectTest() throws Throwable {
        final AtomicInteger count = new AtomicInteger(0);

        AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {

                count.incrementAndGet();
                StackTraceElement e = new StackTraceElement("sun.nio.ch.SocketDispatcher", "read0", null, -1);
                IOException t = new IOException();
                t.setStackTrace(new StackTraceElement[] { e });
                throw t;
            }
        };

        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        try {
            client.prepareGet(getTargetUrl()).execute(handler).get();
            fail("Must have received an exception");
        } catch (ExecutionException ex) {
            assertNotNull(ex);
            assertNotNull(ex.getCause());
            assertEquals(ex.getCause().getClass(), IOException.class);
            assertEquals(count.get(), 1);
        } finally {
            client.close();
        }
    }
}
