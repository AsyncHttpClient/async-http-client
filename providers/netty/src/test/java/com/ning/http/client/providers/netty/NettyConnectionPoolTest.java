/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client.providers.netty;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ConnectionsPool;
import com.ning.http.client.async.ConnectionPoolTest;
import com.ning.http.client.async.ProviderUtil;
import org.jboss.netty.channel.Channel;

import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

public class NettyConnectionPoolTest extends ConnectionPoolTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return ProviderUtil.nettyProvider(config);
    }

    @Override
    public void testInvalidConnectionsPool() {
        ConnectionsPool<String, Channel> cp = new ConnectionsPool<String, Channel>() {

            public boolean offer(String key, Channel connection) {
                return false;
            }

            public Channel poll(String connection) {
                return null;
            }

            public boolean removeAll(Channel connection) {
                return false;
            }

            public boolean canCacheConnection() {
                return false;
            }

            public void destroy() {

            }
        };

        AsyncHttpClient client = getAsyncHttpClient(
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
        client.close();
    }

    @Override
    public void testValidConnectionsPool() {
        ConnectionsPool<String, Channel> cp = new ConnectionsPool<String, Channel>() {

            public boolean offer(String key, Channel connection) {
                return true;
            }

            public Channel poll(String connection) {
                return null;
            }

            public boolean removeAll(Channel connection) {
                return false;
            }

            public boolean canCacheConnection() {
                return true;
            }

            public void destroy() {

            }
        };

        AsyncHttpClient client = getAsyncHttpClient(
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
        client.close();
    }
}
