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
package org.asynchttpclient.reactivestreams;

import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.*;
import static org.testng.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Response;
import org.asynchttpclient.handler.StreamedAsyncHandler;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.testng.annotations.Test;

import rx.Observable;
import rx.RxReactiveStreams;

public class ReactiveStreamsTest extends AbstractBasicTest {

    @Test(groups = "standalone")
    public void testStreamingPutImage() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
            Response response = client.preparePut(getTargetUrl()).setBody(LARGE_IMAGE_PUBLISHER).execute().get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getResponseBodyAsBytes(), LARGE_IMAGE_BYTES);
        }
    }

    @Test(groups = "standalone", enabled = false)
    public void testConnectionDoesNotGetClosed() throws Exception {
        // test that we can stream the same request multiple times
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

    @Test(groups = "standalone", expectedExceptions = ExecutionException.class)
    public void testFailingStream() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
            Observable<ByteBuffer> failingObservable = Observable.error(new FailedStream());
            Publisher<ByteBuffer> failingPublisher = RxReactiveStreams.toPublisher(failingObservable);

            client.preparePut(getTargetUrl()).setBody(failingPublisher).execute().get();
        }
    }

    @SuppressWarnings("serial")
    private class FailedStream extends RuntimeException {
    }

    @Test(groups = "standalone")
    public void streamedResponseTest() throws Throwable {
        try (AsyncHttpClient c = asyncHttpClient()) {

            ListenableFuture<SimpleStreamedAsyncHandler> future = c.preparePost(getTargetUrl()).setBody(LARGE_IMAGE_BYTES).execute(new SimpleStreamedAsyncHandler());

            assertEquals(future.get().getBytes(), LARGE_IMAGE_BYTES);

            // Run it again to check that the pipeline is in a good state
            future = c.preparePost(getTargetUrl()).setBody(LARGE_IMAGE_BYTES).execute(new SimpleStreamedAsyncHandler());

            assertEquals(future.get().getBytes(), LARGE_IMAGE_BYTES);

            // Make sure a regular request still works
            assertEquals(c.preparePost(getTargetUrl()).setBody("Hello").execute().get().getResponseBody(), "Hello");

        }
    }

    @Test(groups = "standalone")
    public void cancelStreamedResponseTest() throws Throwable {
        try (AsyncHttpClient c = asyncHttpClient()) {

            // Cancel immediately
            c.preparePost(getTargetUrl()).setBody(LARGE_IMAGE_BYTES).execute(new CancellingStreamedAsyncProvider(0)).get();

            // Cancel after 1 element
            c.preparePost(getTargetUrl()).setBody(LARGE_IMAGE_BYTES).execute(new CancellingStreamedAsyncProvider(1)).get();

            // Cancel after 10 elements
            c.preparePost(getTargetUrl()).setBody(LARGE_IMAGE_BYTES).execute(new CancellingStreamedAsyncProvider(10)).get();

            // Make sure a regular request works
            assertEquals(c.preparePost(getTargetUrl()).setBody("Hello").execute().get().getResponseBody(), "Hello");
        }
    }

    static protected class SimpleStreamedAsyncHandler implements StreamedAsyncHandler<SimpleStreamedAsyncHandler> {
        private final SimpleSubscriber<HttpResponseBodyPart> subscriber;

        public SimpleStreamedAsyncHandler() {
            this(new SimpleSubscriber<>());
        }

        public SimpleStreamedAsyncHandler(SimpleSubscriber<HttpResponseBodyPart> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public State onStream(Publisher<HttpResponseBodyPart> publisher) {
            publisher.subscribe(subscriber);
            return State.CONTINUE;
        }

        @Override
        public void onThrowable(Throwable t) {
            throw new AssertionError(t);
        }

        @Override
        public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
            throw new AssertionError("Should not have received body part");
        }

        @Override
        public State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
            return State.CONTINUE;
        }

        @Override
        public State onHeadersReceived(HttpResponseHeaders headers) throws Exception {
            return State.CONTINUE;
        }

        @Override
        public SimpleStreamedAsyncHandler onCompleted() throws Exception {
            return this;
        }

        public byte[] getBytes() throws Throwable {
            List<HttpResponseBodyPart> bodyParts = subscriber.getElements();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (HttpResponseBodyPart part : bodyParts) {
                bytes.write(part.getBodyPartBytes());
            }
            return bytes.toByteArray();
        }
    }

    /**
     * Simple subscriber that requests and buffers one element at a time.
     */
    static protected class SimpleSubscriber<T> implements Subscriber<T> {
        private volatile Subscription subscription;
        private volatile Throwable error;
        private final List<T> elements = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(T t) {
            elements.add(t);
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
            latch.countDown();
        }

        @Override
        public void onComplete() {
            latch.countDown();
        }

        public List<T> getElements() throws Throwable {
            latch.await();
            if (error != null) {
                throw error;
            } else {
                return elements;
            }
        }
    }

    static class CancellingStreamedAsyncProvider implements StreamedAsyncHandler<CancellingStreamedAsyncProvider> {
        private final int cancelAfter;

        public CancellingStreamedAsyncProvider(int cancelAfter) {
            this.cancelAfter = cancelAfter;
        }

        @Override
        public State onStream(Publisher<HttpResponseBodyPart> publisher) {
            publisher.subscribe(new CancellingSubscriber<>(cancelAfter));
            return State.CONTINUE;
        }

        @Override
        public void onThrowable(Throwable t) {
            throw new AssertionError(t);
        }

        @Override
        public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
            throw new AssertionError("Should not have received body part");
        }

        @Override
        public State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
            return State.CONTINUE;
        }

        @Override
        public State onHeadersReceived(HttpResponseHeaders headers) throws Exception {
            return State.CONTINUE;
        }

        @Override
        public CancellingStreamedAsyncProvider onCompleted() throws Exception {
            return this;
        }
    }

    /**
     * Simple subscriber that cancels after receiving n elements.
     */
    static class CancellingSubscriber<T> implements Subscriber<T> {
        private final int cancelAfter;

        public CancellingSubscriber(int cancelAfter) {
            this.cancelAfter = cancelAfter;
        }

        private volatile Subscription subscription;
        private volatile int count;

        @Override
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
            if (cancelAfter == 0) {
                subscription.cancel();
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onNext(T t) {
            count++;
            if (count == cancelAfter) {
                subscription.cancel();
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onError(Throwable error) {
        }

        @Override
        public void onComplete() {
        }
    }
}
