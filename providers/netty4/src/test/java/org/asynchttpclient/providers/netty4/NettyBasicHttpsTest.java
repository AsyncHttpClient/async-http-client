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
package org.asynchttpclient.providers.netty4;

import static org.asynchttpclient.async.util.TestUtils.createSSLContext;
import static org.testng.Assert.assertEquals;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.async.BasicHttpsTest;
import org.asynchttpclient.async.util.EventCollectingHandler;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class NettyBasicHttpsTest extends BasicHttpsTest {

    @Test(groups = { "standalone", "default_provider" })
    public void testNormalEventsFired() throws InterruptedException, TimeoutException, ExecutionException {
        try (AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setSSLContext(createSSLContext(new AtomicBoolean(true))).build())) {
            EventCollectingHandler handler = new EventCollectingHandler();
            client.preparePost(getTargetUrl()).setBody("whatever").execute(handler).get(3, TimeUnit.SECONDS);
            handler.waitForCompletion(3, TimeUnit.SECONDS);

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

            assertEquals(handler.firedEvents, expectedEvents, "Got " + Arrays.toString(handler.firedEvents.toArray()));
        }

    }

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }
}
