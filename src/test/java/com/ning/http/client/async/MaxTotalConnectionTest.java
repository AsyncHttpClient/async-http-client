/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public abstract class MaxTotalConnectionTest extends AbstractBasicTest {
    protected final Logger log = LoggerFactory.getLogger(AbstractBasicTest.class);

    @Test(groups = { "standalone", "default_provider" })
    public void testMaxTotalConnectionsExceedingException() throws IOException {
        String[] urls = new String[] { "http://google.com", "http://github.com/" };

        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setConnectTimeout(1000)
                .setRequestTimeout(5000).setAllowPoolingConnections(false).setMaxConnections(1).setMaxConnectionsPerHost(1)
                .build());

        try {
            List<ListenableFuture<Response>> futures = new ArrayList<>();
            for (int i = 0; i < urls.length; i++) {
                futures.add(client.prepareGet(urls[i]).execute());
            }
            
            boolean caughtError = false;
            int i;
            for (i = 0; i < urls.length; i++) {
                try {
                    futures.get(i).get();
                } catch (Exception e) {
                    // assert that 2nd request fails, because maxTotalConnections=1
                    caughtError = true;
                    break;
                }
            }

            Assert.assertEquals(1, i);
            Assert.assertTrue(caughtError);
        } finally {
            client.close();
        }
    }

    @Test
    public void testMaxTotalConnections() throws IOException {
        String[] urls = new String[] { "http://google.com", "http://lenta.ru" };

        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setConnectTimeout(1000)
                .setRequestTimeout(5000).setAllowPoolingConnections(false).setMaxConnections(2).setMaxConnectionsPerHost(1)
                .build());
        try {
            for (String url : urls) {
                client.prepareGet(url).execute();
            }
        } finally {
            client.close();
        }
    }

    /**
     * JFA: Disable this test for 1.2.0 release as it can easily fail because a request may complete before the second one is made, hence failing. The issue occurs frequently on Linux.
     * @throws ExecutionException 
     * @throws InterruptedException 
     */
    @Test(enabled = false)
    public void testMaxTotalConnectionsCorrectExceptionHandling() throws InterruptedException, ExecutionException {
        String[] urls = new String[] { "http://google.com", "http://github.com/" };

        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setConnectTimeout(1000)
                .setRequestTimeout(5000).setAllowPoolingConnections(false).setMaxConnections(1).setMaxConnectionsPerHost(1)
                .build());
        try {
            List<Future<?>> futures = new ArrayList<Future<?>>();
            boolean caughtError = false;
            for (int i = 0; i < urls.length; i++) {
                try {
                    Future<Response> future = client.prepareGet(urls[i]).execute();
                    if (future != null) {
                        futures.add(future);
                    }
                } catch (IOException e) {
                    // assert that 2nd request fails, because maxTotalConnections=1
                    Assert.assertEquals(i, 1);
                    caughtError = true;
                }
            }
            Assert.assertTrue(caughtError);

            // get results of executed requests
            for (Future<?> future : futures) {
                future.get();
            }

            // try to execute once again, expecting that 1 connection is released
            caughtError = false;
            for (int i = 0; i < urls.length; i++) {
                try {
                    client.prepareGet(urls[i]).execute();
                } catch (IOException e) {
                    // assert that 2nd request fails, because maxTotalConnections=1
                    Assert.assertEquals(i, 1);
                    caughtError = true;
                }
            }
            Assert.assertTrue(caughtError);
        } finally {
            client.close();
        }
    }
}
