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
package org.asynchttpclient.request.body;

import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.*;
import static org.testng.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Response;
import org.reactivestreams.Publisher;
import org.testng.annotations.Test;

import rx.Observable;
import rx.RxReactiveStreams;

public class ReactiveStreamsTest extends AbstractBasicTest {

    @Test(groups = { "standalone", "default_provider" }, enabled = true)
    public void testStreamingPutImage() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
            Response response = client.preparePut(getTargetUrl()).setBody(LARGE_IMAGE_PUBLISHER).execute().get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getResponseBodyAsBytes(), LARGE_IMAGE_BYTES);
        }
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = true)
    public void testConnectionDoesNotGetClosed() throws Exception { // test that we can stream the same request multiple times
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
            BoundRequestBuilder requestBuilder = client.preparePut(getTargetUrl()).setBody(LARGE_IMAGE_PUBLISHER);
            Response response = requestBuilder.execute().get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getResponseBodyAsBytes(), LARGE_IMAGE_BYTES);
            
            response = requestBuilder.execute().get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getResponseBodyAsBytes(), LARGE_IMAGE_BYTES);
        }
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = true, expectedExceptions = ExecutionException.class)
    public void testFailingStream() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
            Observable<ByteBuffer> failingObservable = Observable.error(new FailedStream());
            Publisher<ByteBuffer> failingPublisher = RxReactiveStreams.toPublisher(failingObservable);

            client.preparePut(getTargetUrl()).setBody(failingPublisher).execute().get();
        }
    }

    @SuppressWarnings("serial")
    private class FailedStream extends RuntimeException {}
}
