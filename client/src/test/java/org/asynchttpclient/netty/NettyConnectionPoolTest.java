/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved. This program is licensed to you under the Apache License
 * Version 2.0, and you may not use this file except in compliance with the Apache License Version 2.0. You may obtain a
 * copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
 * law or agreed to in writing, software distributed under the Apache License Version 2.0 is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the Apache License Version 2.0
 * for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty;

import static org.asynchttpclient.test.TestUtils.findFreePort;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.pool.ConnectionPoolTest;
import org.asynchttpclient.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.netty.channel.pool.ChannelPool;
import org.asynchttpclient.netty.channel.pool.NoopChannelPool;
import org.testng.annotations.Test;

import java.net.ConnectException;
import java.util.concurrent.TimeUnit;

import io.netty.channel.Channel;

public class NettyConnectionPoolTest extends ConnectionPoolTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testInvalidConnectionsPool() {
        ChannelPool cp = new NoopChannelPool() {

            @Override
            public boolean offer(Channel connection, Object partitionKey) {
                return false;
            }

            @Override
            public boolean isOpen() {
                return false;
            }
        };

        NettyAsyncHttpProviderConfig providerConfig = new NettyAsyncHttpProviderConfig();
        providerConfig.setChannelPool(cp);
        try (AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setAsyncHttpClientProviderConfig(providerConfig)
                .build())) {
            Exception exception = null;
            try {
                client.prepareGet(getTargetUrl()).execute().get(TIMEOUT, TimeUnit.SECONDS);
            } catch (Exception ex) {
                ex.printStackTrace();
                exception = ex;
            }
            assertNotNull(exception);
            assertNotNull(exception.getCause());
            assertEquals(exception.getCause().getMessage(), "Pool is already closed");
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testValidConnectionsPool() {
        ChannelPool cp = new NoopChannelPool() {

            @Override
            public boolean offer(Channel connection, Object partitionKey) {
                return true;
            }
        };

        NettyAsyncHttpProviderConfig providerConfig = new NettyAsyncHttpProviderConfig();
        providerConfig.setChannelPool(cp);
        try (AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setAsyncHttpClientProviderConfig(providerConfig)
                .build())) {
            Exception exception = null;
            try {
                client.prepareGet(getTargetUrl()).execute().get(TIMEOUT, TimeUnit.SECONDS);
            } catch (Exception ex) {
                ex.printStackTrace();
                exception = ex;
            }
            assertNull(exception);
        }
    }

    @Test
    public void testHostNotContactable() {

        try (AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build())) {
            String url = null;
            try {
                url = "http://127.0.0.1:" + findFreePort();
            } catch (Exception e) {
                fail("unable to find free port to simulate downed host");
            }
            int i;
            for (i = 0; i < 2; i++) {
                try {
                    log.info("{} requesting url [{}]...", i, url);
                    Response response = client.prepareGet(url).execute().get();
                    log.info("{} response [{}].", i, response);
                    fail("Shouldn't be here: should get an exception instead");
                } catch (Exception ex) {
                    assertNotNull(ex.getCause());
                    Throwable cause = ex.getCause();
                    assertTrue(cause instanceof ConnectException);
                }
            }
        }
    }
}
