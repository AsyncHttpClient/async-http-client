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

/**
 * Reactive Streams publisher for streaming HTTP response body parts.
 * <p>
 * This publisher implements backpressure-aware streaming of HTTP response body parts
 * using the Reactive Streams API. It extends Netty's HandlerPublisher to integrate
 * with the Netty pipeline and manages demand-driven read operations.
 * </p>
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Used internally with StreamedAsyncHandler
 * StreamedAsyncHandler<Response> handler = new StreamedAsyncHandler<Response>() {
 *     @Override
 *     public State onStream(Publisher<HttpResponseBodyPart> publisher) {
 *         publisher.subscribe(new Subscriber<HttpResponseBodyPart>() {
 *             public void onNext(HttpResponseBodyPart part) {
 *                 // Process body part with backpressure
 *                 subscription.request(1);
 *             }
 *         });
 *         return State.CONTINUE;
 *     }
 * };
 * }</pre>
 */
public class StreamedResponsePublisher extends HandlerPublisher<HttpResponseBodyPart> {

  protected final Logger logger = LoggerFactory.getLogger(getClass());

  private final ChannelManager channelManager;
  private final NettyResponseFuture<?> future;
  private final Channel channel;
  private volatile boolean hasOutstandingRequest = false;
  private Throwable error;

  /**
   * Constructs a new StreamedResponsePublisher.
   *
   * @param executor the event executor for processing events
   * @param channelManager the channel manager for channel lifecycle management
   * @param future the response future associated with this stream
   * @param channel the channel from which body parts are read
   */
  StreamedResponsePublisher(EventExecutor executor, ChannelManager channelManager, NettyResponseFuture<?> future, Channel channel) {
    super(executor, HttpResponseBodyPart.class);
    this.channelManager = channelManager;
    this.future = future;
    this.channel = channel;
  }

  /**
   * Handles cancellation by the subscriber.
   * <p>
   * When the subscriber cancels the subscription, this method marks the future as done
   * and closes the channel since the remaining response body will not be consumed.
   * </p>
   */
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

  /**
   * Handles demand requests from the subscriber.
   * <p>
   * This method is called when the subscriber requests more items. It sets the
   * outstanding request flag to indicate that the subscriber is ready to receive data.
   * </p>
   */
  @Override
  protected void requestDemand() {
    hasOutstandingRequest = true;
    super.requestDemand();
  }

  /**
   * Handles channel read completion events.
   * <p>
   * This method clears the outstanding request flag when a read completes, indicating
   * that the current demand has been satisfied. It then delegates to the parent
   * implementation to trigger backpressure handling.
   * </p>
   *
   * @param ctx the channel handler context
   * @throws Exception if an error occurs during processing
   */
  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
    hasOutstandingRequest = false;
    super.channelReadComplete(ctx);
  }

  /**
   * Subscribes a subscriber to this publisher.
   * <p>
   * This method wraps the provided subscriber with an ErrorReplacingSubscriber
   * to handle error replacement during completion, allowing deferred errors
   * to be signaled at the appropriate time.
   * </p>
   *
   * @param subscriber the subscriber to receive body parts
   */
  @Override
  public void subscribe(Subscriber<? super HttpResponseBodyPart> subscriber) {
    super.subscribe(new ErrorReplacingSubscriber(subscriber));
  }

  /**
   * Checks if there is an outstanding demand request from the subscriber.
   *
   * @return true if the subscriber has requested data that hasn't been fulfilled yet
   */
  public boolean hasOutstandingRequest() {
    return hasOutstandingRequest;
  }

  /**
   * Returns the response future associated with this publisher.
   *
   * @return the NettyResponseFuture for this streaming response
   */
  NettyResponseFuture<?> future() {
    return future;
  }

  /**
   * Sets an error that should be delivered to the subscriber on completion.
   * <p>
   * This allows deferring error signaling until the natural completion point
   * of the stream, converting a successful completion into an error signal.
   * </p>
   *
   * @param t the error to deliver on completion
   */
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
