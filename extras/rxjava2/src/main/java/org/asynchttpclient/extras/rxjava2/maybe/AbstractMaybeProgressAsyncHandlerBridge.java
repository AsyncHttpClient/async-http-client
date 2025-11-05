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

import io.reactivex.MaybeEmitter;
import org.asynchttpclient.handler.ProgressAsyncHandler;

/**
 * An extension to {@code AbstractMaybeAsyncHandlerBridge} for {@code ProgressAsyncHandlers}.
 * <p>
 * This bridge adds support for progress tracking callbacks during content write operations,
 * while maintaining the same disposal and error handling semantics as the base class.
 *
 * @param <T> the result type produced by the wrapped {@code ProgressAsyncHandler} and emitted via RxJava
 */
public abstract class AbstractMaybeProgressAsyncHandlerBridge<T> extends AbstractMaybeAsyncHandlerBridge<T>
        implements ProgressAsyncHandler<Void> {

  /**
   * Creates a new progress-aware handler bridge.
   *
   * @param emitter the RxJava MaybeEmitter to bridge to
   */
  protected AbstractMaybeProgressAsyncHandlerBridge(MaybeEmitter<T> emitter) {
    super(emitter);
  }

  /**
   * Called when request headers have been written.
   * <p>
   * Aborts the request if the emitter has been disposed, otherwise delegates
   * to the underlying handler.
   *
   * @return the handler state indicating whether to continue or abort
   */
  @Override
  public final State onHeadersWritten() {
    return emitter.isDisposed() ? disposed() : delegate().onHeadersWritten();
  }

  /**
   * Called when request content has been written.
   * <p>
   * Aborts the request if the emitter has been disposed, otherwise delegates
   * to the underlying handler.
   *
   * @return the handler state indicating whether to continue or abort
   */
  @Override
  public final State onContentWritten() {
    return emitter.isDisposed() ? disposed() : delegate().onContentWritten();
  }

  /**
   * Called to report progress of content write operations.
   * <p>
   * Aborts the request if the emitter has been disposed, otherwise delegates
   * to the underlying handler.
   *
   * @param amount the amount of bytes written in this update
   * @param current the total number of bytes written so far
   * @param total the total number of bytes to write
   * @return the handler state indicating whether to continue or abort
   */
  @Override
  public final State onContentWriteProgress(long amount, long current, long total) {
    return emitter.isDisposed() ? disposed() : delegate().onContentWriteProgress(amount, current, total);
  }

  /**
   * Returns the underlying ProgressAsyncHandler to which operations are delegated.
   *
   * @return the delegate handler
   */
  @Override
  protected abstract ProgressAsyncHandler<? extends T> delegate();

}
