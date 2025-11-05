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
package org.asynchttpclient.extras.rxjava.single;

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.extras.rxjava.UnsubscribedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.SingleSubscriber;
import rx.exceptions.CompositeException;
import rx.exceptions.Exceptions;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Abstract bridge implementation that adapts AsyncHandler to RxJava SingleSubscriber.
 * <p>
 * This bridge handles the integration between AsyncHttpClient's callback-based AsyncHandler
 * and RxJava's subscriber model, managing state transitions and error handling.
 */
abstract class AbstractSingleSubscriberBridge<T> implements AsyncHandler<Void> {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractSingleSubscriberBridge.class);

  protected final SingleSubscriber<T> subscriber;

  private final AtomicBoolean delegateTerminated = new AtomicBoolean();

  /**
   * Creates a new subscriber bridge.
   *
   * @param subscriber the RxJava SingleSubscriber to bridge to
   */
  protected AbstractSingleSubscriberBridge(SingleSubscriber<T> subscriber) {
    this.subscriber = requireNonNull(subscriber);
  }

  /**
   * Called when a response body part is received.
   * <p>
   * Aborts the request if the subscriber has unsubscribed, otherwise delegates
   * to the underlying handler.
   *
   * @param content the received body part
   * @return the handler state indicating whether to continue or abort
   * @throws Exception if an error occurs during processing
   */
  @Override
  public State onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
    return subscriber.isUnsubscribed() ? abort() : delegate().onBodyPartReceived(content);
  }

  /**
   * Called when the response status is received.
   * <p>
   * Aborts the request if the subscriber has unsubscribed, otherwise delegates
   * to the underlying handler.
   *
   * @param status the received response status
   * @return the handler state indicating whether to continue or abort
   * @throws Exception if an error occurs during processing
   */
  @Override
  public State onStatusReceived(HttpResponseStatus status) throws Exception {
    return subscriber.isUnsubscribed() ? abort() : delegate().onStatusReceived(status);
  }

  /**
   * Called when response headers are received.
   * <p>
   * Aborts the request if the subscriber has unsubscribed, otherwise delegates
   * to the underlying handler.
   *
   * @param headers the received headers
   * @return the handler state indicating whether to continue or abort
   * @throws Exception if an error occurs during processing
   */
  @Override
  public State onHeadersReceived(HttpHeaders headers) throws Exception {
    return subscriber.isUnsubscribed() ? abort() : delegate().onHeadersReceived(headers);
  }

  /**
   * Called when trailing headers are received.
   * <p>
   * Aborts the request if the subscriber has unsubscribed, otherwise delegates
   * to the underlying handler.
   *
   * @param headers the received trailing headers
   * @return the handler state indicating whether to continue or abort
   * @throws Exception if an error occurs during processing
   */
  @Override
  public State onTrailingHeadersReceived(HttpHeaders headers) throws Exception {
    return subscriber.isUnsubscribed() ? abort() : delegate().onTrailingHeadersReceived(headers);
  }

  /**
   * Called when the request completes successfully.
   * <p>
   * Delegates to the underlying handler to produce the result, then emits
   * it to the subscriber via onSuccess unless the subscriber has unsubscribed.
   *
   * @return null (Void return type)
   */
  @Override
  public Void onCompleted() {
    if (delegateTerminated.getAndSet(true)) {
      return null;
    }

    final T result;
    try {
      result = delegate().onCompleted();
    } catch (final Throwable t) {
      emitOnError(t);
      return null;
    }

    if (!subscriber.isUnsubscribed()) {
      subscriber.onSuccess(result);
    }

    return null;
  }

  /**
   * Called when an error occurs during request processing.
   * <p>
   * Delegates to the underlying handler, then emits the error to the subscriber.
   * If the delegate throws an exception, both exceptions are combined into a
   * CompositeException.
   *
   * @param t the error that occurred
   */
  @Override
  public void onThrowable(Throwable t) {
    if (delegateTerminated.getAndSet(true)) {
      return;
    }

    Throwable error = t;
    try {
      delegate().onThrowable(t);
    } catch (final Throwable x) {
      error = new CompositeException(Arrays.asList(t, x));
    }

    emitOnError(error);
  }

  /**
   * Aborts the request and notifies the delegate handler.
   * <p>
   * This method is called when the subscriber unsubscribes. It ensures the
   * delegate handler receives an UnsubscribedException for cleanup purposes.
   *
   * @return ABORT state to signal request termination
   */
  protected AsyncHandler.State abort() {
    if (!delegateTerminated.getAndSet(true)) {
      // send a terminal event to the delegate
      // e.g. to trigger cleanup logic
      delegate().onThrowable(new UnsubscribedException());
    }

    return State.ABORT;
  }

  /**
   * Returns the underlying AsyncHandler to which operations are delegated.
   *
   * @return the delegate handler
   */
  protected abstract AsyncHandler<? extends T> delegate();

  private void emitOnError(Throwable error) {
    Exceptions.throwIfFatal(error);
    if (!subscriber.isUnsubscribed()) {
      subscriber.onError(error);
    } else {
      LOGGER.debug("Not propagating onError after unsubscription: {}", error.getMessage(), error);
    }
  }
}
