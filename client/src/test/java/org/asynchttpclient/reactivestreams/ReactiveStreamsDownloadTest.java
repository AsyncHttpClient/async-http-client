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
package org.asynchttpclient.reactivestreams;

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.handler.StreamedAsyncHandler;
import org.asynchttpclient.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ReactiveStreamsDownloadTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReactiveStreamsDownloadTest.class);

    private static final int serverPort = 8080;
    private static File largeFile;
    private static File smallFile;

    @BeforeAll
    public static void setUpBeforeTest() throws Exception {
        largeFile = TestUtils.createTempFile(15 * 1024);
        smallFile = TestUtils.createTempFile(20);
        HttpStaticFileServer.start(serverPort);
    }

    @AfterAll
    public static void tearDown() {
        HttpStaticFileServer.shutdown();
    }

    @Test
    public void streamedResponseLargeFileTest() throws Throwable {
        try (AsyncHttpClient client = asyncHttpClient()) {
            String largeFileName = "http://localhost:" + serverPort + '/' + largeFile.getName();
            ListenableFuture<SimpleStreamedAsyncHandler> future = client.prepareGet(largeFileName).execute(new SimpleStreamedAsyncHandler());
            byte[] result = future.get().getBytes();
            assertEquals(largeFile.length(), result.length);
        }
    }

    @Test
    public void streamedResponseSmallFileTest() throws Throwable {
        try (AsyncHttpClient client = asyncHttpClient()) {
            String smallFileName = "http://localhost:" + serverPort + '/' + smallFile.getName();
            ListenableFuture<SimpleStreamedAsyncHandler> future = client.prepareGet(smallFileName).execute(new SimpleStreamedAsyncHandler());
            byte[] result = future.get().getBytes();
            LOGGER.debug("Result file size: " + result.length);
            assertEquals(smallFile.length(), result.length);
        }
    }

    protected static class SimpleStreamedAsyncHandler implements StreamedAsyncHandler<SimpleStreamedAsyncHandler> {
        private final SimpleSubscriber<HttpResponseBodyPart> subscriber;

        SimpleStreamedAsyncHandler() {
            this(new SimpleSubscriber<>());
        }

        SimpleStreamedAsyncHandler(SimpleSubscriber<HttpResponseBodyPart> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public State onStream(Publisher<HttpResponseBodyPart> publisher) {
            LOGGER.debug("SimpleStreamedAsyncHandlerOnCompleted onStream");
            publisher.subscribe(subscriber);
            return State.CONTINUE;
        }

        @Override
        public void onThrowable(Throwable t) {
            throw new AssertionError(t);
        }

        @Override
        public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
            LOGGER.debug("SimpleStreamedAsyncHandlerOnCompleted onBodyPartReceived");
            throw new AssertionError("Should not have received body part");
        }

        @Override
        public State onStatusReceived(HttpResponseStatus responseStatus) {
            return State.CONTINUE;
        }

        @Override
        public State onHeadersReceived(HttpHeaders headers) {
            return State.CONTINUE;
        }

        @Override
        public SimpleStreamedAsyncHandler onCompleted() {
            LOGGER.debug("SimpleStreamedAsyncHandlerOnCompleted onSubscribe");
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
    protected static class SimpleSubscriber<T> implements Subscriber<T> {
        private final List<T> elements = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile Subscription subscription;
        private volatile Throwable error;

        @Override
        public void onSubscribe(Subscription subscription) {
            LOGGER.debug("SimpleSubscriber onSubscribe");
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(T t) {
            LOGGER.debug("SimpleSubscriber onNext");
            elements.add(t);
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            LOGGER.error("SimpleSubscriber onError");
            this.error = error;
            latch.countDown();
        }

        @Override
        public void onComplete() {
            LOGGER.debug("SimpleSubscriber onComplete");
            latch.countDown();
        }

        public List<T> getElements() throws Throwable {
            latch.await();
            if (error != null) {
                throw error;
            } else {
                return Collections.unmodifiableList(elements);
            }
        }
    }
}
