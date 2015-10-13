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
package org.asynchttpclient.channel;

import static org.asynchttpclient.Dsl.newConfig;
import static org.testng.Assert.assertNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

public class MaxTotalConnectionTest extends AbstractBasicTest {
    protected final Logger log = LoggerFactory.getLogger(AbstractBasicTest.class);

    @Test(groups = { "standalone", "default_provider" })
    public void testMaxTotalConnectionsExceedingException() throws IOException {
        String[] urls = new String[] { "http://google.com", "http://github.com/" };

        AsyncHttpClientConfig config = newConfig().connectTimeout(1000)
                .requestTimeout(5000).allowPoolingConnections(false).maxConnections(1).maxConnectionsPerHost(1)
                .build();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
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
        }
    }

    @Test
    public void testMaxTotalConnections() throws Exception {
        String[] urls = new String[] { "http://google.com", "http://lenta.ru" };

        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicReference<Throwable> ex = new AtomicReference<>();
        final AtomicReference<String> failedUrl = new AtomicReference<>();

        AsyncHttpClientConfig config = newConfig().connectTimeout(1000).requestTimeout(5000)
                .allowPoolingConnections(false).maxConnections(2).maxConnectionsPerHost(1).build();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            for (String url : urls) {
                final String thisUrl = url;
                client.prepareGet(url).execute(new AsyncCompletionHandlerBase() {
                    @Override
                    public Response onCompleted(Response response) throws Exception {
                        Response r = super.onCompleted(response);
                        latch.countDown();
                        return r;
                    }

                    @Override
                    public void onThrowable(Throwable t) {
                        super.onThrowable(t);
                        ex.set(t);
                        failedUrl.set(thisUrl);
                        latch.countDown();
                    }
                });
            }

            latch.await();
            assertNull(ex.get());
            assertNull(failedUrl.get());
        }
    }
}
