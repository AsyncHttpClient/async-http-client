/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.request.body;

import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_BYTES;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_PUBLISHER;
import static org.testng.Assert.assertEquals;

import java.nio.ByteBuffer;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.asynchttpclient.netty.NettyProviderUtil;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.ReactiveStreamsTest;
import org.asynchttpclient.request.body.generator.BodyGenerator;
import org.asynchttpclient.request.body.generator.ReactiveStreamsBodyGenerator;
import org.reactivestreams.Publisher;
import org.testng.annotations.Test;

public class NettyReactiveStreamsTest extends ReactiveStreamsTest {
    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }

    @Test(groups = { "standalone" }, enabled = true)
    public void testEagerPutImage() throws Exception { // this tests the `ReactiveStreamBodyGenerator.createBody` implementation
        try (AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeout(100 * 6000).build())) {
            BodyGenerator bodyGenerator = new GenericBodyGenerator(createBody(LARGE_IMAGE_PUBLISHER));
            Response response = client.preparePut(getTargetUrl()).setBody(bodyGenerator).execute().get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getResponseBodyAsBytes(), LARGE_IMAGE_BYTES);
        }
    }

    public BodyGenerator createBody(Publisher<ByteBuffer> publisher) {
        return new ReactiveStreamsBodyGenerator(publisher);
    }

    // Because NettyRequestFactory#body uses the type information to decide the type of body to create, this class allows to hide
    // the type so that the generic `NettyBodyBody` is used.
    // This is useful to test that the implementation of ReactiveStreamBodyGenerator#createBody works as expected.
    private class GenericBodyGenerator implements BodyGenerator {

        private final BodyGenerator bodyGenerator;

        public GenericBodyGenerator(BodyGenerator bodyGenerator) {
            this.bodyGenerator = bodyGenerator;
        }

        @Override
        public Body createBody() {
            return this.bodyGenerator.createBody();
        }
    }
}
