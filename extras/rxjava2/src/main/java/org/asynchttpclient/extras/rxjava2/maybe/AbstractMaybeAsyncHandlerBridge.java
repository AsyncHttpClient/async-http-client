/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.extras.rxjava2.maybe;

import io.netty.handler.codec.http.HttpHeaders;
import io.reactivex.MaybeEmitter;
import io.reactivex.exceptions.CompositeException;
import io.reactivex.exceptions.Exceptions;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.extras.rxjava2.DisposedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Abstract base class that bridges events between the {@code Maybe} reactive base type and {@code AsyncHandlers}.
 * <p>
 * When an event is received, it's first checked if the Rx stream has been disposed asynchronously. If so, request
 * processing is {@linkplain #disposed() aborted}, otherwise, the event is forwarded to the {@linkplain #delegate()
 * wrapped handler}.
 * <p>
 * When the request is {@link AsyncHandler#onCompleted() completed}, the result produced by the wrapped instance is
 * forwarded to the {@code Maybe}: If the result is {@code null}, {@link MaybeEmitter#onComplete()} is invoked,
 * {@link MaybeEmitter#onSuccess(Object)} otherwise.
 * <p>
 * Any errors during request processing are forwarded via {@link MaybeEmitter#onError(Throwable)}.
 *
 * @param <T> the result type produced by the wrapped {@code AsyncHandler} and emitted via RxJava
 */
public abstract class AbstractMaybeAsyncHandlerBridge<T> implements AsyncHandler<Void> {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractMaybeAsyncHandlerBridge.class);

  private static volatile DisposedException sharedDisposed;

  /**
   * The Rx callback object that receives downstream events and will be queried for its
   * {@link MaybeEmitter#isDisposed() disposed state} when Async HTTP Client callbacks are invoked.
   */
  protected final MaybeEmitter<T> emitter;

  /**
   * Indicates if the delegate has already received a terminal event.
   */
  private final AtomicBoolean delegateTerminated = new AtomicBoolean();

  protected AbstractMaybeAsyncHandlerBridge(MaybeEmitter<T> emitter) {
    this.emitter = requireNonNull(emitter);
  }

  @Override
  public final State onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
    return emitter.isDisposed() ? disposed() : delegate().onBodyPartReceived(content);
  }

  @Override
  public final State onStatusReceived(HttpResponseStatus status) throws Exception {
    return emitter.isDisposed() ? disposed() : delegate().onStatusReceived(status);
  }

  @Override
  public final State onHeadersReceived(HttpHeaders headers) throws Exception {
    return emitter.isDisposed() ? disposed() : delegate().onHeadersReceived(headers);
  }

  @Override
  public State onTrailingHeadersReceived(HttpHeaders headers) throws Exception {
    return emitter.isDisposed() ? disposed() : delegate().onTrailingHeadersReceived(headers);
  }

  /**
   * {@inheritDoc}
   * <p>
   * <p>
   * The value returned by the wrapped {@code AsyncHandler} won't be returned by this method, but emtited via RxJava.
   * </p>
   *
   * @return always {@code null}
   */
  @Override
  public final Void onCompleted() {
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

    if (!emitter.isDisposed()) {
      if (result == null) {
        emitter.onComplete();
      } else {
        emitter.onSuccess(result);
      }
    }

    return null;
  }

  /**
   * {@inheritDoc}
   * <p>
   * <p>
   * The exception will first be propagated to the wrapped {@code AsyncHandler}, then emitted via RxJava. If the
   * invocation of the delegate itself throws an exception, both the original exception and the follow-up exception
   * will be wrapped into RxJava's {@code CompositeException} and then be emitted.
   * </p>
   */
  @Override
  public final void onThrowable(Throwable t) {
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
   * Called to indicate that request processing is to be aborted because the linked Rx stream has been disposed. If
   * the {@link #delegate() delegate} didn't already receive a terminal event,
   * {@code AsyncHandler#onThrowable(Throwable) onThrowable} will be called with a {@link DisposedException}.
   *
   * @return always {@link State#ABORT}
   */
  protected final AsyncHandler.State disposed() {
    if (!delegateTerminated.getAndSet(true)) {

      DisposedException disposed = sharedDisposed;
      if (disposed == null) {
        disposed = new DisposedException("Subscription has been disposed.");
        final StackTraceElement[] stackTrace = disposed.getStackTrace();
        if (stackTrace.length > 0) {
          disposed.setStackTrace(new StackTraceElement[]{stackTrace[0]});
        }

        sharedDisposed = disposed;
      }

      delegate().onThrowable(disposed);
    }

    return State.ABORT;
  }

  /**
   * @return the wrapped {@code AsyncHandler} instance to which calls are delegated
   */
  protected abstract AsyncHandler<? extends T> delegate();

  private void emitOnError(Throwable error) {
    Exceptions.throwIfFatal(error);
    if (!emitter.isDisposed()) {
      emitter.onError(error);
    } else {
      LOGGER.debug("Not propagating onError after disposal: {}", error.getMessage(), error);
    }
  }
}
