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
package org.asynchttpclient.async;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.testng.Assert.*;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public abstract class MaxTotalConnectionTest extends AbstractBasicTest {
    protected final Logger log = LoggerFactory.getLogger(AbstractBasicTest.class);

    @Test(groups = { "standalone", "default_provider" })
    public void testMaxTotalConnectionsExceedingException() {
        String[] urls = new String[] { "http://google.com", "http://github.com/" };

        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setConnectionTimeoutInMs(1000).setRequestTimeoutInMs(5000).setAllowPoolingConnection(false).setMaximumConnectionsTotal(1).setMaximumConnectionsPerHost(1).build());
        try {
            boolean caughtError = false;
            for (int i = 0; i < urls.length; i++) {
                try {
                    client.prepareGet(urls[i]).execute();
                } catch (IOException e) {
                    // assert that 2nd request fails, because maxTotalConnections=1
                    assertEquals(i, 1);
                    caughtError = true;
                }
            }
            assertTrue(caughtError);
        } finally {
            client.close();
        }
    }

    @Test
    public void testMaxTotalConnections() {
        String[] urls = new String[] { "http://google.com", "http://lenta.ru" };

        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setConnectionTimeoutInMs(1000).setRequestTimeoutInMs(5000).setAllowPoolingConnection(false).setMaximumConnectionsTotal(2).setMaximumConnectionsPerHost(1).build());
        try {
            for (String url : urls) {
                try {
                    client.prepareGet(url).execute();
                } catch (IOException e) {
                    fail("Smth wrong with connections handling!");
                }
            }
        } finally {
            client.close();
        }
    }

    /**
     * JFA: Disable this test for 1.2.0 release as it can easily fail because a request may complete before the second one is made, hence failing. The issue occurs frequently on Linux.
     */
    @Test(enabled = false)
    public void testMaxTotalConnectionsCorrectExceptionHandling() {
        String[] urls = new String[] { "http://google.com", "http://github.com/" };

        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setConnectionTimeoutInMs(1000).setRequestTimeoutInMs(5000).setAllowPoolingConnection(false).setMaximumConnectionsTotal(1).setMaximumConnectionsPerHost(1).build());
        try {
            List<Future<Response>> futures = new ArrayList<Future<Response>>();
            boolean caughtError = false;
            for (int i = 0; i < urls.length; i++) {
                try {
                    Future<Response> future = client.prepareGet(urls[i]).execute();
                    if (future != null) {
                        futures.add(future);
                    }
                } catch (IOException e) {
                    // assert that 2nd request fails, because maxTotalConnections=1
                    assertEquals(i, 1);
                    caughtError = true;
                }
            }
            assertTrue(caughtError);

            // get results of executed requests
            for (Future<Response> future : futures) {
                try {
                    /* Response res = */future.get();
                } catch (InterruptedException e) {
                    log.error("Error!", e);
                } catch (ExecutionException e) {
                    log.error("Error!", e);
                }
            }

            // try to execute once again, expecting that 1 connection is released
            caughtError = false;
            for (int i = 0; i < urls.length; i++) {
                try {
                    client.prepareGet(urls[i]).execute();
                } catch (IOException e) {
                    // assert that 2nd request fails, because maxTotalConnections=1
                    assertEquals(i, 1);
                    caughtError = true;
                }
            }
            assertTrue(caughtError);
        } finally {
            client.close();
        }
    }
}
