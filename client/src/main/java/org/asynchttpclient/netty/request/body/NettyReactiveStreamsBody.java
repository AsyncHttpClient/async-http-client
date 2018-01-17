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

import com.typesafe.netty.HandlerSubscriber;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.NoSuchElementException;

import static org.asynchttpclient.util.Assertions.assertNotNull;

public class NettyReactiveStreamsBody implements NettyBody {

  private static final Logger LOGGER = LoggerFactory.getLogger(NettyReactiveStreamsBody.class);
  private static final String NAME_IN_CHANNEL_PIPELINE = "request-body-streamer";

  private final Publisher<ByteBuf> publisher;

  private final long contentLength;

  public NettyReactiveStreamsBody(Publisher<ByteBuf> publisher, long contentLength) {
    this.publisher = publisher;
    this.contentLength = contentLength;
  }

  @Override
  public long getContentLength() {
    return contentLength;
  }

  @Override
  public void write(Channel channel, NettyResponseFuture<?> future) {
    if (future.isStreamConsumed()) {
      LOGGER.warn("Stream has already been consumed and cannot be reset");
    } else {
      future.setStreamConsumed(true);
      NettySubscriber subscriber = new NettySubscriber(channel, future);
      channel.pipeline().addLast(NAME_IN_CHANNEL_PIPELINE, subscriber);
      publisher.subscribe(new SubscriberAdapter(subscriber));
      subscriber.delayedStart();
    }
  }

  private static class SubscriberAdapter implements Subscriber<ByteBuf> {
    private final Subscriber<HttpContent> subscriber;

    SubscriberAdapter(Subscriber<HttpContent> subscriber) {
      this.subscriber = subscriber;
    }

    @Override
    public void onSubscribe(Subscription s) {
      subscriber.onSubscribe(s);
    }

    @Override
    public void onNext(ByteBuf buffer) {
      HttpContent content = new DefaultHttpContent(buffer);
      subscriber.onNext(content);
    }

    @Override
    public void onError(Throwable t) {
      subscriber.onError(t);
    }

    @Override
    public void onComplete() {
      subscriber.onComplete();
    }
  }

  private static class NettySubscriber extends HandlerSubscriber<HttpContent> {
    private static final Logger LOGGER = LoggerFactory.getLogger(NettySubscriber.class);

    private final Channel channel;
    private final NettyResponseFuture<?> future;
    private volatile Subscription deferredSubscription;

    NettySubscriber(Channel channel, NettyResponseFuture<?> future) {
      super(channel.eventLoop());
      this.channel = channel;
      this.future = future;
    }

    @Override
    protected void complete() {
      channel.eventLoop().execute(() -> channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
              .addListener(future -> removeFromPipeline()));
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      deferredSubscription = subscription;
    }

    void delayedStart() {
      super.onSubscribe(deferredSubscription);
    }

    @Override
    protected void error(Throwable error) {
      assertNotNull(error, "error");
      removeFromPipeline();
      future.abort(error);
    }

    private void removeFromPipeline() {
      try {
        channel.pipeline().remove(this);
        LOGGER.debug(String.format("Removed handler %s from pipeline.", NAME_IN_CHANNEL_PIPELINE));
      } catch (NoSuchElementException e) {
        LOGGER.debug(String.format("Failed to remove handler %s from pipeline.", NAME_IN_CHANNEL_PIPELINE), e);
      }
    }
  }
}
