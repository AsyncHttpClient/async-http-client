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
import org.asynchttpclient.AsyncHandler;

import static java.util.Objects.requireNonNull;

/**
 * Concrete bridge implementation for standard AsyncHandlers.
 * <p>
 * This bridge adapts a regular AsyncHandler to work with RxJava 2 Maybe,
 * without progress tracking support. It handles the conversion between
 * AsyncHttpClient's callback-based API and RxJava's reactive API.
 *
 * @param <T> the result type produced by the wrapped AsyncHandler and emitted via RxJava
 */
public final class MaybeAsyncHandlerBridge<T> extends AbstractMaybeAsyncHandlerBridge<T> {

  private final AsyncHandler<? extends T> delegate;

  /**
   * Creates a new async handler bridge.
   *
   * @param emitter the RxJava MaybeEmitter to bridge to
   * @param delegate the AsyncHandler to delegate operations to
   */
  public MaybeAsyncHandlerBridge(MaybeEmitter<T> emitter, AsyncHandler<? extends T> delegate) {
    super(emitter);
    this.delegate = requireNonNull(delegate);
  }

  /**
   * Returns the underlying AsyncHandler delegate.
   *
   * @return the delegate handler
   */
  @Override
  protected AsyncHandler<? extends T> delegate() {
    return delegate;
  }
}
