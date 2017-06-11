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

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_BYTES;
import static org.testng.Assert.assertEquals;
import io.netty.handler.codec.http.HttpHeaders;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Response;
import org.asynchttpclient.handler.StreamedAsyncHandler;
import org.asynchttpclient.test.TestUtils;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.testng.annotations.Test;

import rx.Observable;
import rx.RxReactiveStreams;

public class ReactiveStreamsTest extends AbstractBasicTest {

    public static Publisher<ByteBuffer> createPublisher(final byte[] bytes, final int chunkSize) {
        Observable<ByteBuffer> observable = Observable.from(new ByteBufferIterable(bytes, chunkSize));
        return RxReactiveStreams.toPublisher(observable);
    }

    @Test(groups = "standalone")
    public void testStreamingPutImage() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
            Response response = client.preparePut(getTargetUrl()).setBody(createPublisher(LARGE_IMAGE_BYTES, 2342)).execute().get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getResponseBodyAsBytes(), LARGE_IMAGE_BYTES);
        }
    }

    @Test(groups = "standalone")
    public void testConnectionDoesNotGetClosed() throws Exception {
        // test that we can stream the same request multiple times
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
            String expectedMd5 = TestUtils.md5(LARGE_IMAGE_BYTES);
            BoundRequestBuilder requestBuilder = client.preparePut(getTargetUrl())//
                    .setBody(createPublisher(LARGE_IMAGE_BYTES, 1000))//
                    .setHeader("X-" + CONTENT_LENGTH, LARGE_IMAGE_BYTES.length)//
                    .setHeader("X-" + CONTENT_MD5, expectedMd5);

            Response response = requestBuilder.execute().get();
            assertEquals(response.getStatusCode(), 200);
            byte[] responseBody = response.getResponseBodyAsBytes();
            responseBody = response.getResponseBodyAsBytes();
            assertEquals(Integer.valueOf(response.getHeader("X-" + CONTENT_LENGTH)).intValue(), LARGE_IMAGE_BYTES.length, "Server side payload length invalid");
            assertEquals(responseBody.length, LARGE_IMAGE_BYTES.length, "Client side payload length invalid");
            assertEquals(response.getHeader(CONTENT_MD5), expectedMd5, "Server side payload MD5 invalid");
            assertEquals(TestUtils.md5(responseBody), expectedMd5, "Client side payload MD5 invalid");
            assertEquals(responseBody, LARGE_IMAGE_BYTES);

            response = requestBuilder.execute().get();
            assertEquals(response.getStatusCode(), 200);
            responseBody = response.getResponseBodyAsBytes();
            assertEquals(Integer.valueOf(response.getHeader("X-" + CONTENT_LENGTH)).intValue(), LARGE_IMAGE_BYTES.length, "Server side payload length invalid");
            assertEquals(responseBody.length, LARGE_IMAGE_BYTES.length, "Client side payload length invalid");
            try {
                assertEquals(response.getHeader(CONTENT_MD5), expectedMd5, "Server side payload MD5 invalid");
                assertEquals(TestUtils.md5(responseBody), expectedMd5, "Client side payload MD5 invalid");
                assertEquals(responseBody, LARGE_IMAGE_BYTES);
            } catch (AssertionError e) {
                e.printStackTrace();
                for (int i = 0; i < LARGE_IMAGE_BYTES.length; i++) {
                    assertEquals(responseBody[i], LARGE_IMAGE_BYTES[i], "Invalid response byte at position " + i);
                }
                throw e;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        ReactiveStreamsTest test = new ReactiveStreamsTest();
        test.setUpGlobal();
        try {
            for (int i = 0; i < 1000; i++) {
                test.testConnectionDoesNotGetClosed();
            }
        } finally {
            test.tearDownGlobal();
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

    static class SimpleStreamedAsyncHandler implements StreamedAsyncHandler<SimpleStreamedAsyncHandler> {
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
        public State onHeadersReceived(HttpHeaders headers) throws Exception {
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
    static class SimpleSubscriber<T> implements Subscriber<T> {
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
        public State onHeadersReceived(HttpHeaders headers) throws Exception {
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

    static class ByteBufferIterable implements Iterable<ByteBuffer> {
        private final byte[] payload;
        private final int chunkSize;

        public ByteBufferIterable(byte[] payload, int chunkSize) {
            this.payload = payload;
            this.chunkSize = chunkSize;
        }

        @Override
        public Iterator<ByteBuffer> iterator() {
            return new Iterator<ByteBuffer>() {
                private volatile int currentIndex = 0;

                @Override
                public boolean hasNext() {
                    return currentIndex != payload.length;
                }

                @Override
                public ByteBuffer next() {
                    int thisCurrentIndex = currentIndex;
                    int length = Math.min(chunkSize, payload.length - thisCurrentIndex);
                    currentIndex += length;
                    return ByteBuffer.wrap(payload, thisCurrentIndex, length);
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException("ByteBufferIterable's iterator does not support remove.");
                }
            };
        }
    }
}
