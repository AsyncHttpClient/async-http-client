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

abstract class AbstractSingleSubscriberBridge<T> implements AsyncHandler<Void> {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractSingleSubscriberBridge.class);

  protected final SingleSubscriber<T> subscriber;

  private final AtomicBoolean delegateTerminated = new AtomicBoolean();

  protected AbstractSingleSubscriberBridge(SingleSubscriber<T> subscriber) {
    this.subscriber = requireNonNull(subscriber);
  }

  @Override
  public State onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
    return subscriber.isUnsubscribed() ? abort() : delegate().onBodyPartReceived(content);
  }

  @Override
  public State onStatusReceived(HttpResponseStatus status) throws Exception {
    return subscriber.isUnsubscribed() ? abort() : delegate().onStatusReceived(status);
  }

  @Override
  public State onHeadersReceived(HttpHeaders headers) throws Exception {
    return subscriber.isUnsubscribed() ? abort() : delegate().onHeadersReceived(headers);
  }

  @Override
  public State onTrailingHeadersReceived(HttpHeaders headers) throws Exception {
    return subscriber.isUnsubscribed() ? abort() : delegate().onTrailingHeadersReceived(headers);
  }

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

  protected AsyncHandler.State abort() {
    if (!delegateTerminated.getAndSet(true)) {
      // send a terminal event to the delegate
      // e.g. to trigger cleanup logic
      delegate().onThrowable(new UnsubscribedException());
    }

    return State.ABORT;
  }

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
