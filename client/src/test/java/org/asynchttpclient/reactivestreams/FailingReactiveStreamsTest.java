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

import io.netty.channel.Channel;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.netty.handler.StreamedResponsePublisher;
import org.asynchttpclient.reactivestreams.ReactiveStreamsTest.SimpleStreamedAsyncHandler;
import org.asynchttpclient.reactivestreams.ReactiveStreamsTest.SimpleSubscriber;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_BYTES;
import static org.testng.Assert.assertTrue;

public class FailingReactiveStreamsTest extends AbstractBasicTest {

  @Test
  public void testRetryingOnFailingStream() throws Exception {
    try (AsyncHttpClient client = asyncHttpClient()) {
      final CountDownLatch streamStarted = new CountDownLatch(1); // allows us to wait until subscriber has received the first body chunk
      final CountDownLatch streamOnHold = new CountDownLatch(1); // allows us to hold the subscriber from processing further body chunks
      final CountDownLatch replayingRequest = new CountDownLatch(1); // allows us to block until the request is being replayed ( this is what we want to test here!)

      // a ref to the publisher is needed to get a hold on the channel (if there is a better way, this should be changed)
      final AtomicReference<StreamedResponsePublisher> publisherRef = new AtomicReference<>(null);

      // executing the request
      client.preparePost(getTargetUrl()).setBody(LARGE_IMAGE_BYTES)
              .execute(new ReplayedSimpleAsyncHandler(replayingRequest, new BlockedStreamSubscriber(streamStarted, streamOnHold)) {
                @Override
                public State onStream(Publisher<HttpResponseBodyPart> publisher) {
                  if (!(publisher instanceof StreamedResponsePublisher)) {
                    throw new IllegalStateException(String.format("publisher %s is expected to be an instance of %s", publisher, StreamedResponsePublisher.class));
                  } else if (!publisherRef.compareAndSet(null, (StreamedResponsePublisher) publisher)) {
                    // abort on retry
                    return State.ABORT;
                  }
                  return super.onStream(publisher);
                }
              });

      // before proceeding, wait for the subscriber to receive at least one body chunk
      streamStarted.await();
      // The stream has started, hence `StreamedAsyncHandler.onStream(publisher)` was called, and `publisherRef` was initialized with the `publisher` passed to `onStream`
      assertTrue(publisherRef.get() != null, "Expected a not null publisher.");

      // close the channel to emulate a connection crash while the response body chunks were being received.
      StreamedResponsePublisher publisher = publisherRef.get();
      final CountDownLatch channelClosed = new CountDownLatch(1);

      getChannel(publisher).close().addListener(future-> channelClosed.countDown());
      streamOnHold.countDown(); // the subscriber is set free to process new incoming body chunks.
      channelClosed.await(); // the channel is confirmed to be closed

      // now we expect a new connection to be created and AHC retry logic to kick-in automatically
      replayingRequest.await(); // wait until we are notified the request is being replayed

      // Change this if there is a better way of stating the test succeeded
      assertTrue(true);
    }
  }

  private Channel getChannel(StreamedResponsePublisher publisher) throws Exception {
    Field field = publisher.getClass().getDeclaredField("channel");
    field.setAccessible(true);
    return (Channel) field.get(publisher);
  }

  private static class BlockedStreamSubscriber extends SimpleSubscriber<HttpResponseBodyPart> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlockedStreamSubscriber.class);
    private final CountDownLatch streamStarted;
    private final CountDownLatch streamOnHold;

    BlockedStreamSubscriber(CountDownLatch streamStarted, CountDownLatch streamOnHold) {
      this.streamStarted = streamStarted;
      this.streamOnHold = streamOnHold;
    }

    @Override
    public void onNext(HttpResponseBodyPart t) {
      streamStarted.countDown();
      try {
        streamOnHold.await();
      } catch (InterruptedException e) {
        LOGGER.error("`streamOnHold` latch was interrupted", e);
      }
      super.onNext(t);
    }
  }

  private static class ReplayedSimpleAsyncHandler extends SimpleStreamedAsyncHandler {
    private final CountDownLatch replaying;

    ReplayedSimpleAsyncHandler(CountDownLatch replaying, SimpleSubscriber<HttpResponseBodyPart> subscriber) {
      super(subscriber);
      this.replaying = replaying;
    }

    @Override
    public void onRetry() {
      replaying.countDown();
    }
  }
}
