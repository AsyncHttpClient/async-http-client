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
package org.asynchttpclient.providers.netty;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import io.netty.channel.Channel;

import java.util.concurrent.TimeUnit;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.async.ConnectionPoolTest;
import org.asynchttpclient.providers.netty.channel.ChannelPool;
import org.testng.annotations.Test;

public class NettyConnectionPoolTest extends ConnectionPoolTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testInvalidConnectionsPool() {
        ChannelPool cp = new ChannelPool() {

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

        NettyAsyncHttpProviderConfig providerConfig = new NettyAsyncHttpProviderConfig();
        providerConfig.setChannelPool(cp);
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setAsyncHttpClientProviderConfig(providerConfig).build());
        try {
            Exception exception = null;
            try {
                client.prepareGet(getTargetUrl()).execute().get(TIMEOUT, TimeUnit.SECONDS);
            } catch (Exception ex) {
                ex.printStackTrace();
                exception = ex;
            }
            assertNotNull(exception);
            assertEquals(exception.getMessage(), "Too many connections -1");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testValidConnectionsPool() {
        ChannelPool cp = new ChannelPool() {

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

        NettyAsyncHttpProviderConfig providerConfig = new NettyAsyncHttpProviderConfig();
        providerConfig.setChannelPool(cp);
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setAsyncHttpClientProviderConfig(providerConfig).build());
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
}
