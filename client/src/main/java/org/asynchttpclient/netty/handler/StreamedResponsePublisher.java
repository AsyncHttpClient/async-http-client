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
package org.asynchttpclient.netty.handler;

import com.typesafe.netty.HandlerPublisher;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.EventExecutor;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamedResponsePublisher extends HandlerPublisher<HttpResponseBodyPart> {

  protected final Logger logger = LoggerFactory.getLogger(getClass());

  private final ChannelManager channelManager;
  private final NettyResponseFuture<?> future;
  private final Channel channel;
  private volatile boolean hasOutstandingRequest = false;
  private Throwable error;

  StreamedResponsePublisher(EventExecutor executor, ChannelManager channelManager, NettyResponseFuture<?> future, Channel channel) {
    super(executor, HttpResponseBodyPart.class);
    this.channelManager = channelManager;
    this.future = future;
    this.channel = channel;
  }

  @Override
  protected void cancelled() {
    logger.debug("Subscriber cancelled, ignoring the rest of the body");

    try {
      future.done();
    } catch (Exception t) {
      // Never propagate exception once we know we are done.
      logger.debug(t.getMessage(), t);
    }

    // The subscriber cancelled early - this channel is dead and should be closed.
    channelManager.closeChannel(channel);
  }

  @Override
  protected void requestDemand() {
    hasOutstandingRequest = true;
    super.requestDemand();
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
    hasOutstandingRequest = false;
    super.channelReadComplete(ctx);
  }

  @Override
  public void subscribe(Subscriber<? super HttpResponseBodyPart> subscriber) {
    super.subscribe(new ErrorReplacingSubscriber(subscriber));
  }

  public boolean hasOutstandingRequest() {
    return hasOutstandingRequest;
  }

  NettyResponseFuture<?> future() {
    return future;
  }

  public void setError(Throwable t) {
    this.error = t;
  }

  private class ErrorReplacingSubscriber implements Subscriber<HttpResponseBodyPart> {

    private final Subscriber<? super HttpResponseBodyPart> subscriber;

    ErrorReplacingSubscriber(Subscriber<? super HttpResponseBodyPart> subscriber) {
      this.subscriber = subscriber;
    }

    @Override
    public void onSubscribe(Subscription s) {
      subscriber.onSubscribe(s);
    }

    @Override
    public void onNext(HttpResponseBodyPart httpResponseBodyPart) {
      subscriber.onNext(httpResponseBodyPart);
    }

    @Override
    public void onError(Throwable t) {
      subscriber.onError(t);
    }

    @Override
    public void onComplete() {
      Throwable replacementError = error;
      if (replacementError == null) {
        subscriber.onComplete();
      } else {
        subscriber.onError(replacementError);
      }
    }
  }
}
