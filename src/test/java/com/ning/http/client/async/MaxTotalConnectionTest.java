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

import static org.testng.Assert.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import com.ning.http.client.AsyncCompletionHandlerBase;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

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

            assertEquals(i, 1);
            assertTrue(caughtError);
        } finally {
            client.close();
        }
    }

    @Test
    public void testMaxTotalConnections() throws InterruptedException {
        String[] urls = new String[] { "http://google.com", "http://lenta.ru" };

        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setConnectTimeout(1000).setRequestTimeout(5000)
                .setAllowPoolingConnections(false).setMaxConnections(2).setMaxConnectionsPerHost(1).build());

        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicReference<String> failedUrl = new AtomicReference<String>();

        try {
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
                        failedUrl.set(thisUrl);
                        latch.countDown();
                    }
                });
            }

            latch.await();
            assertNull(failedUrl.get());

        } finally {
            client.close();
        }
    }
}
