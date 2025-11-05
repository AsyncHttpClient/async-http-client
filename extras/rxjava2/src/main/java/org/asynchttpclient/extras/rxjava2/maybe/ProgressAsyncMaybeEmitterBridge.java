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

import static java.util.Objects.requireNonNull;

/**
 * Concrete bridge implementation for ProgressAsyncHandlers.
 * <p>
 * This bridge adapts a ProgressAsyncHandler to work with RxJava 2 Maybe,
 * providing progress tracking support for content write operations. It handles
 * the conversion between AsyncHttpClient's callback-based API and RxJava's
 * reactive API while supporting progress callbacks.
 *
 * @param <T> the result type produced by the wrapped ProgressAsyncHandler and emitted via RxJava
 */
public final class ProgressAsyncMaybeEmitterBridge<T> extends AbstractMaybeProgressAsyncHandlerBridge<T> {

  private final ProgressAsyncHandler<? extends T> delegate;

  /**
   * Creates a new progress-aware async handler bridge.
   *
   * @param emitter the RxJava MaybeEmitter to bridge to
   * @param delegate the ProgressAsyncHandler to delegate operations to
   */
  public ProgressAsyncMaybeEmitterBridge(MaybeEmitter<T> emitter, ProgressAsyncHandler<? extends T> delegate) {
    super(emitter);
    this.delegate = requireNonNull(delegate);
  }

  /**
   * Returns the underlying ProgressAsyncHandler delegate.
   *
   * @return the delegate handler
   */
  @Override
  protected ProgressAsyncHandler<? extends T> delegate() {
    return delegate;
  }
}
