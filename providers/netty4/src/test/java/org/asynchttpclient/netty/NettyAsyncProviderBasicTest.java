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
package org.asynchttpclient.netty;

import static org.testng.Assert.assertEquals;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpProviderConfig;
import org.asynchttpclient.AsyncProvidersBasicTest;
import org.asynchttpclient.config.AsyncHttpClientConfig;
import org.asynchttpclient.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.request.Request;
import org.asynchttpclient.request.RequestBuilder;
import org.asynchttpclient.test.EventCollectingHandler;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelOption;

public class NettyAsyncProviderBasicTest extends AsyncProvidersBasicTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }

    @Override
    protected AsyncHttpProviderConfig<?, ?> getProviderConfig() {
        return new NettyAsyncHttpProviderConfig().addChannelOption(ChannelOption.TCP_NODELAY, Boolean.TRUE);
    }

    @Override
    protected String acceptEncodingHeader() {
        return "gzip,deflate";
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void testNewConnectionEventsFired() throws Exception {
        Request request = new RequestBuilder("GET").setUrl("http://127.0.0.1:" + port1 + "/Test").build();

        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            EventCollectingHandler handler = new EventCollectingHandler();
            client.executeRequest(request, handler).get(3, TimeUnit.SECONDS);
            handler.waitForCompletion(3, TimeUnit.SECONDS);

            List<String> expectedEvents = Arrays.asList(
                    "PoolConnection",
                    "OpenConnection",
                    "DnsResolved",
                    "ConnectionOpen",
                    "SendRequest",
                    "HeaderWriteCompleted",
                    "StatusReceived",
                    "HeadersReceived",
                    "Completed");

            assertEquals(handler.firedEvents, expectedEvents, "Got " + Arrays.toString(handler.firedEvents.toArray()));
        }

    }
}
