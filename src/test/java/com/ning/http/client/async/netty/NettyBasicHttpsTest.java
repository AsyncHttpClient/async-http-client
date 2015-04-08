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
package com.ning.http.client.async.netty;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

import com.google.common.base.Joiner;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.async.BasicHttpsTest;
import com.ning.http.client.async.EventCollectingHandler;
import com.ning.http.client.async.ProviderUtil;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class NettyBasicHttpsTest extends BasicHttpsTest {

    @Test
    public void testNormalEventsFired() throws InterruptedException, TimeoutException, ExecutionException {
        try (AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setSSLContext(createSSLContext(new AtomicBoolean(true))).build())) {
            EventCollectingHandler handler = new EventCollectingHandler();
            client.preparePost(getTargetUrl()).setBody("whatever").execute(handler).get(3, TimeUnit.SECONDS);
            handler.waitForCompletion();

            List<String> expectedEvents = Arrays.asList(
                    "PoolConnection",
                    "OpenConnection",
                    "DnsResolved",
                    "SslHandshakeCompleted",
                    "ConnectionOpen",
                    "SendRequest",
                    "HeaderWriteCompleted",
                    "StatusReceived",
                    "HeadersReceived",
                    "Completed");

            assertEquals(handler.firedEvents, expectedEvents,
                    "Got: " + Joiner.on(", ").join(handler.firedEvents));
        }
    }

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return ProviderUtil.nettyProvider(config);
    }
}
