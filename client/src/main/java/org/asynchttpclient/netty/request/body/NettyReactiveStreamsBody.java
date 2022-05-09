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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

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
      // we need to put this handler before HttpHandler to handle LastHttpContent on our own (see NettySubscriber.channelRead)
      channel.pipeline().addBefore(ChannelManager.AHC_HTTP_HANDLER, NAME_IN_CHANNEL_PIPELINE, subscriber);
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

    private static final Subscription DO_NOT_DELAY = new Subscription() {
      public void cancel() {}
      public void request(long l) {}
    };

    private final Channel channel;
    private final NettyResponseFuture<?> future;
    private final AtomicReference<Subscription> deferredSubscription = new AtomicReference<>();
    private final AtomicReference<Object> lastContent = new AtomicReference<>(null);
    private static final Object BODY_SENT_PLACEHOLDER = new Object();
    private static class HttpContentAndContext {
        private final ChannelHandlerContext ctx;
        private final LastHttpContent lastHttpContent;

        private HttpContentAndContext(ChannelHandlerContext ctx, LastHttpContent lastHttpContent) {
            this.ctx = ctx;
            this.lastHttpContent = lastHttpContent;
        }
    }

    NettySubscriber(Channel channel, NettyResponseFuture<?> future) {
      super(channel.eventLoop());
      this.channel = channel;
      this.future = future;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof LastHttpContent) {
        // if last http content is received before we acknowledged sending of request last http content,
        // channel will be returned to pool and possible will be used by other thread for request.
        // That request will add its own NettySubscriber and will crash.
        // So we keep it here until our request is done.
        if (this.lastContent.compareAndSet(null, new HttpContentAndContext(ctx, (LastHttpContent) msg))) {
          return;
        }
      }
      super.channelRead(ctx, msg);
    }

    @Override
    protected void complete() {
      removeFromPipeline();
      channel.eventLoop().execute(() -> {
        ChannelPromise promise = channel.newPromise().addListener(future -> {
          if (!lastContent.compareAndSet(null, BODY_SENT_PLACEHOLDER)) {
            // LastHttpContent arrived before we sent full request, we should reproduce its read
            HttpContentAndContext contentAndContext = (HttpContentAndContext) lastContent.get();
            channelRead(contentAndContext.ctx, contentAndContext.lastHttpContent);
          }
        });
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, promise);
      });
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      if (!deferredSubscription.compareAndSet(null, subscription)) {
        super.onSubscribe(subscription);
      }
    }

    void delayedStart() {
      // If we won the race against onSubscribe, we need to tell it
      // know not to delay, because we won't be called again.
      Subscription subscription = deferredSubscription.getAndSet(DO_NOT_DELAY);
      if (subscription != null) {
        super.onSubscribe(subscription);
      }
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
