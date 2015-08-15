/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.async.netty;

import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.async.ChunkingTest;
import com.ning.http.client.async.ProviderUtil;
import com.ning.http.client.providers.netty.request.body.FeedableBodyGenerator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class NettyChunkingTest extends ChunkingTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return ProviderUtil.nettyProvider(config);
    }

    @Test()
    public void testDirectFileWithFeedableBodyGenerator() throws Throwable {
        doTestWithFeedableBodyGenerator(new ByteArrayInputStream(LARGE_IMAGE_BYTES));
    }

    private void doTestWithFeedableBodyGenerator(InputStream is) throws Throwable {
        AsyncHttpClientConfig.Builder bc = httpClientBuilder();

        try (AsyncHttpClient c = getAsyncHttpClient(bc.build())) {

            RequestBuilder builder = new RequestBuilder("POST");
            builder.setUrl(getTargetUrl());
            final FeedableBodyGenerator feedableBodyGenerator = new FeedableBodyGenerator();
            builder.setBody(feedableBodyGenerator);

            Request r = builder.build();

            final ListenableFuture<Response> responseFuture = c.executeRequest(r);

            feed(feedableBodyGenerator, is);

            waitForAndAssertResponse(responseFuture);
        }
    }

    private void feed(FeedableBodyGenerator feedableBodyGenerator, InputStream is) throws IOException {
        try (InputStream inputStream = is) {
            byte[] buffer = new byte[512];
            for (int i = 0; (i = inputStream.read(buffer)) > -1;) {
                byte[] chunk = new byte[i];
                System.arraycopy(buffer, 0, chunk, 0, i);
                feedableBodyGenerator.feed(ByteBuffer.wrap(chunk), false);
            }
        }
        feedableBodyGenerator.feed(ByteBuffer.allocate(0), true);

    }
}
