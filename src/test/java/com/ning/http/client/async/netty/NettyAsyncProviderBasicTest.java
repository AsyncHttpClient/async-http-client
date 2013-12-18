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

import com.ning.http.client.*;
import com.ning.http.client.async.AsyncProvidersBasicTest;
import com.ning.http.client.async.ProviderUtil;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;

public class NettyAsyncProviderBasicTest extends AsyncProvidersBasicTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return ProviderUtil.nettyProvider(config);
    }

    @Override
    protected AsyncHttpProviderConfig<?, ?> getProviderConfig() {
        final NettyAsyncHttpProviderConfig config = 
                new NettyAsyncHttpProviderConfig();
        config.addProperty("tcpNoDelay", true);
        return config;
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    @Override
    public void asyncDoPostBasicGZIPTest() throws Throwable {
        AsyncHttpClientConfig cf = new AsyncHttpClientConfig.Builder().setCompressionEnabled(true).build();
        AsyncHttpClient client = getAsyncHttpClient(cf);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_");
                sb.append(i);
                sb.append("=value_");
                sb.append(i);
                sb.append("&");
            }
            sb.setLength(sb.length() - 1);

            client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getStatusCode(), 200);
                        assertEquals(response.getHeader("X-Accept-Encoding"), "gzip,deflate");
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            }).get();
            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                Assert.fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

}
