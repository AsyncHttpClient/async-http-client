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
package org.asynchttpclient.request.body.generator;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.request.body.Body;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReactiveStreamsBodyGenerator implements FeedableBodyGenerator {
    private static final ByteBuffer EMPTY = ByteBuffer.wrap("".getBytes());

    private final Publisher<ByteBuffer> publisher;
    private final FeedableBodyGenerator feedableBodyGenerator;
    private volatile FeedListener feedListener;

    public ReactiveStreamsBodyGenerator(Publisher<ByteBuffer> publisher) {
        this.publisher = publisher;
        this.feedableBodyGenerator = new UnboundedQueueFeedableBodyGenerator();
    }

    public Publisher<ByteBuffer> getPublisher() {
        return this.publisher;
    }

    @Override
    public boolean feed(ByteBuffer buffer, boolean isLast) throws Exception {
        return feedableBodyGenerator.feed(buffer, isLast);
    }

    @Override
    public void setListener(FeedListener listener) {
        feedListener = listener;
        feedableBodyGenerator.setListener(listener);
    }

    @Override
    public Body createBody() {
        return new StreamedBody(publisher, feedableBodyGenerator);
    }

    private class StreamedBody implements Body {
        private final AtomicBoolean initialized = new AtomicBoolean(false);

        private final SimpleSubscriber subscriber;
        private final Body body;

        public StreamedBody(Publisher<ByteBuffer> publisher, FeedableBodyGenerator bodyGenerator) {
            this.body = bodyGenerator.createBody();
            this.subscriber = new SimpleSubscriber(bodyGenerator);
        }

        @Override
        public void close() throws IOException {
            body.close();
        }

        @Override
        public long getContentLength() {
            return body.getContentLength();
        }

        @Override
        public BodyState transferTo(ByteBuf target) throws IOException {
            if (initialized.compareAndSet(false, true))
                publisher.subscribe(subscriber);

            return body.transferTo(target);
        }
    }

    private class SimpleSubscriber implements Subscriber<ByteBuffer> {

        private final Logger LOGGER = LoggerFactory.getLogger(SimpleSubscriber.class);

        private final FeedableBodyGenerator feeder;
        private volatile Subscription subscription;

        public SimpleSubscriber(FeedableBodyGenerator feeder) {
            this.feeder = feeder;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (s == null)
                throw null;

            // If someone has made a mistake and added this Subscriber multiple times, let's handle it gracefully
            if (this.subscription != null) {
                s.cancel(); // Cancel the additional subscription
            } else {
                subscription = s;
                subscription.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(ByteBuffer t) {
            if (t == null)
                throw null;
            try {
                feeder.feed(t, false);
            } catch (Exception e) {
                LOGGER.error("Exception occurred while processing element in stream.", e);
                subscription.cancel();
            }
        }

        @Override
        public void onError(Throwable t) {
            if (t == null)
                throw null;
            LOGGER.debug("Error occurred while consuming body stream.", t);
            FeedListener listener = feedListener;
            if (listener != null)
                listener.onError(t);
        }

        @Override
        public void onComplete() {
            try {
                feeder.feed(EMPTY, true);
            } catch (Exception e) {
                LOGGER.info("Ignoring exception occurred while completing stream processing.", e);
                this.subscription.cancel();
            }
        }
    }
}
