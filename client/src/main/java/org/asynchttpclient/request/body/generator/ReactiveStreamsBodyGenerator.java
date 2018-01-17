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
import io.netty.buffer.Unpooled;
import org.asynchttpclient.request.body.Body;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.asynchttpclient.util.Assertions.assertNotNull;

public class ReactiveStreamsBodyGenerator implements FeedableBodyGenerator {

  private final Publisher<ByteBuf> publisher;
  private final FeedableBodyGenerator feedableBodyGenerator;
  private final long contentLength;
  private volatile FeedListener feedListener;

  /**
   * Creates a Streamable Body which takes a Content-Length.
   * If the contentLength parameter is -1L a Http Header of Transfer-Encoding: chunked will be set.
   * Otherwise it will set the Content-Length header to the value provided
   *
   * @param publisher     Body as a Publisher
   * @param contentLength Content-Length of the Body
   */
  public ReactiveStreamsBodyGenerator(Publisher<ByteBuf> publisher, long contentLength) {
    this.publisher = publisher;
    this.feedableBodyGenerator = new UnboundedQueueFeedableBodyGenerator();
    this.contentLength = contentLength;
  }

  public Publisher<ByteBuf> getPublisher() {
    return this.publisher;
  }

  @Override
  public boolean feed(ByteBuf buffer, boolean isLast) throws Exception {
    return feedableBodyGenerator.feed(buffer, isLast);
  }

  @Override
  public void setListener(FeedListener listener) {
    feedListener = listener;
    feedableBodyGenerator.setListener(listener);
  }

  public long getContentLength() {
    return contentLength;
  }

  @Override
  public Body createBody() {
    return new StreamedBody(feedableBodyGenerator, contentLength);
  }

  private class StreamedBody implements Body {
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private final SimpleSubscriber subscriber;
    private final Body body;

    private final long contentLength;

    public StreamedBody(FeedableBodyGenerator bodyGenerator, long contentLength) {
      this.body = bodyGenerator.createBody();
      this.subscriber = new SimpleSubscriber(bodyGenerator);
      this.contentLength = contentLength;
    }

    @Override
    public void close() throws IOException {
      body.close();
    }

    @Override
    public long getContentLength() {
      return contentLength;
    }

    @Override
    public BodyState transferTo(ByteBuf target) throws IOException {
      if (initialized.compareAndSet(false, true)) {
        publisher.subscribe(subscriber);
      }

      return body.transferTo(target);
    }
  }

  private class SimpleSubscriber implements Subscriber<ByteBuf> {

    private final Logger LOGGER = LoggerFactory.getLogger(SimpleSubscriber.class);

    private final FeedableBodyGenerator feeder;
    private volatile Subscription subscription;

    public SimpleSubscriber(FeedableBodyGenerator feeder) {
      this.feeder = feeder;
    }

    @Override
    public void onSubscribe(Subscription s) {
      assertNotNull(s, "subscription");

      // If someone has made a mistake and added this Subscriber multiple times, let's handle it gracefully
      if (this.subscription != null) {
        s.cancel(); // Cancel the additional subscription
      } else {
        subscription = s;
        subscription.request(Long.MAX_VALUE);
      }
    }

    @Override
    public void onNext(ByteBuf b) {
      assertNotNull(b, "bytebuf");
      try {
        feeder.feed(b, false);
      } catch (Exception e) {
        LOGGER.error("Exception occurred while processing element in stream.", e);
        subscription.cancel();
      }
    }

    @Override
    public void onError(Throwable t) {
      assertNotNull(t, "throwable");
      LOGGER.debug("Error occurred while consuming body stream.", t);
      FeedListener listener = feedListener;
      if (listener != null) {
        listener.onError(t);
      }
    }

    @Override
    public void onComplete() {
      try {
        feeder.feed(Unpooled.EMPTY_BUFFER, true);
      } catch (Exception e) {
        LOGGER.info("Ignoring exception occurred while completing stream processing.", e);
        this.subscription.cancel();
      }
    }
  }
}
