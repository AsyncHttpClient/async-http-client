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
 * Default implementation of the {@code RxHttpClient} interface.
 * <p>
 * This class provides RxJava 2 integration for AsyncHttpClient by wrapping
 * HTTP requests in {@code Maybe} instances, supporting both standard and
 * progress-aware async handlers.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * AsyncHttpClient client = asyncHttpClient();
 * RxHttpClient rxClient = new DefaultRxHttpClient(client);
 *
 * // Simple request returning Response
 * Request request = get("http://www.example.com").build();
 * Maybe<Response> maybe = rxClient.prepare(request);
 * maybe.subscribe(
 *     response -> System.out.println("Status: " + response.getStatusCode()),
 *     error -> System.err.println("Error: " + error),
 *     () -> System.out.println("Completed with no result")
 * );
 *
 * // Custom handler for specific result type
 * Maybe<String> bodyMaybe = rxClient.prepare(
 *     request,
 *     () -> new AsyncCompletionHandler<String>() {
 *         public String onCompleted(Response response) {
 *             return response.getResponseBody();
 *         }
 *     }
 * );
 * }</pre>
 */
public class DefaultRxHttpClient implements RxHttpClient {

  private final AsyncHttpClient asyncHttpClient;

  /**
   * Creates a new DefaultRxHttpClient that delegates to the given AsyncHttpClient.
   *
   * @param asyncHttpClient the Async HTTP Client instance to be used for executing requests
   * @throws NullPointerException if {@code asyncHttpClient} is {@code null}
   */
  public DefaultRxHttpClient(AsyncHttpClient asyncHttpClient) {
    this.asyncHttpClient = requireNonNull(asyncHttpClient);
  }

  /**
   * Prepares a request for execution as a RxJava 2 Maybe.
   * <p>
   * The request is executed when the Maybe is subscribed to, and the result
   * is produced by the handler supplied by the handlerSupplier.
   *
   * @param request the HTTP request to execute
   * @param handlerSupplier a supplier that provides the AsyncHandler for processing the response
   * @param <T> the type of result produced by the handler and emitted by the Maybe
   * @return a Maybe that executes the request on subscription and emits the handler's result
   * @throws NullPointerException if {@code request} or {@code handlerSupplier} is {@code null}
   */
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
