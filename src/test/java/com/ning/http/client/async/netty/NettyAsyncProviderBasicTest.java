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
import com.ning.http.client.AsyncHttpProviderConfig;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.async.AsyncProvidersBasicTest;
import com.ning.http.client.async.EventCollectingHandler;
import com.ning.http.client.async.ProviderUtil;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Test
public class NettyAsyncProviderBasicTest extends AsyncProvidersBasicTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return ProviderUtil.nettyProvider(config);
    }

    @Override
    protected AsyncHttpProviderConfig<?, ?> getProviderConfig() {
        return new NettyAsyncHttpProviderConfig().addProperty("tcpNoDelay", true);
    }

    @Override
    protected String generatedAcceptEncodingHeader() {
        return "gzip,deflate";
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void testNewConnectionEventsFired() throws InterruptedException, TimeoutException, ExecutionException {
        Request request = new RequestBuilder("GET").setUrl("http://127.0.0.1:" + port1 + "/Test").build();

        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            EventCollectingHandler handler = new EventCollectingHandler();
            client.executeRequest(request, handler).get(3, TimeUnit.SECONDS);
            handler.waitForCompletion();

            List<String> expectedEvents = Arrays.asList("PoolConnection",//
                    "OpenConnection",//
                    "DnsResolved",//
                    "ConnectionOpen",//
                    "SendRequest",//
                    "HeaderWriteCompleted",//
                    "StatusReceived",//
                    "HeadersReceived",//
                    "Completed");

            assertEquals(handler.firedEvents, expectedEvents, "Got: " + Joiner.on(", ").join(handler.firedEvents));
        }
    }

    @Test(enabled = false)
    public void requestingPlainHttpEndpointOverHttpsThrowsSslException() throws Throwable {
        super.requestingPlainHttpEndpointOverHttpsThrowsSslException();
    }
}
