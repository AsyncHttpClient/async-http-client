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

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Response;
import com.ning.http.client.logging.LogManager;
import com.ning.http.client.logging.Logger;
import org.testng.annotations.Test;


import java.io.IOException;

import static org.testng.Assert.*;

public class ConnectionPoolTest extends AbstractBasicTest{
    protected final Logger log = LogManager.getLogger(AbstractBasicTest.class);
   
    @Test
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
        for (int i = 0; i < 3; i++) {
            try {
                log.info(String.format("%d requesting url [%s]...", i, url));
                Response response = client.prepareGet(url).execute().get();
                log.info(String.format("%d response [%s].", i, response));
                if (i > 1) {
                    fail();
                }
            } catch (Exception e) {
                assertEquals(e.getClass(), IOException.class);
                assertEquals(e.getMessage(), "Too many connections");
                log.error(String.format("%d error: %s", i, e.getMessage()));
            }
        }
    }
}
