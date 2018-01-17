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
package org.asynchttpclient.extras.rxjava2;

import io.reactivex.Maybe;
import io.reactivex.MaybeEmitter;
import io.reactivex.disposables.Disposables;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Request;
import org.asynchttpclient.extras.rxjava2.maybe.MaybeAsyncHandlerBridge;
import org.asynchttpclient.extras.rxjava2.maybe.ProgressAsyncMaybeEmitterBridge;
import org.asynchttpclient.handler.ProgressAsyncHandler;

import java.util.concurrent.Future;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Straight forward default implementation of the {@code RxHttpClient} interface.
 */
public class DefaultRxHttpClient implements RxHttpClient {

  private final AsyncHttpClient asyncHttpClient;

  /**
   * Returns a new {@code DefaultRxHttpClient} instance that uses the given {@code asyncHttpClient} under the hoods.
   *
   * @param asyncHttpClient the Async HTTP Client instance to be used
   * @throws NullPointerException if {@code asyncHttpClient} is {@code null}
   */
  public DefaultRxHttpClient(AsyncHttpClient asyncHttpClient) {
    this.asyncHttpClient = requireNonNull(asyncHttpClient);
  }

  @Override
  public <T> Maybe<T> prepare(Request request, Supplier<? extends AsyncHandler<T>> handlerSupplier) {
    requireNonNull(request);
    requireNonNull(handlerSupplier);

    return Maybe.create(emitter -> {
      final AsyncHandler<?> bridge = createBridge(emitter, handlerSupplier.get());
      final Future<?> responseFuture = asyncHttpClient.executeRequest(request, bridge);
      emitter.setDisposable(Disposables.fromFuture(responseFuture));
    });
  }

  /**
   * Creates an {@code AsyncHandler} that bridges events from the given {@code handler} to the given {@code emitter}
   * and cancellation/disposal in the other direction.
   *
   * @param <T>     the result type produced by {@code handler} and emitted by {@code emitter}
   * @param emitter the RxJava emitter instance that receives results upon completion and will be queried for disposal
   *                during event processing
   * @param handler the {@code AsyncHandler} instance that receives downstream events and produces the result that will be
   *                emitted upon request completion
   * @return the bridge handler
   */
  protected <T> AsyncHandler<?> createBridge(MaybeEmitter<T> emitter, AsyncHandler<T> handler) {
    if (handler instanceof ProgressAsyncHandler) {
      return new ProgressAsyncMaybeEmitterBridge<>(emitter, (ProgressAsyncHandler<? extends T>) handler);
    }

    return new MaybeAsyncHandlerBridge<>(emitter, handler);
  }
}
