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

import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.handler.ProgressAsyncHandler;
import rx.Single;
import rx.SingleSubscriber;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

import java.util.concurrent.Future;

import static java.util.Objects.requireNonNull;

/**
 * Wraps HTTP requests into RxJava {@code Single} instances.
 * <p>
 * This utility class provides methods to create RxJava Singles from AsyncHttpClient
 * requests, enabling reactive programming patterns for HTTP operations.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * AsyncHttpClient client = asyncHttpClient();
 *
 * // Simple Single from BoundRequestBuilder
 * Single<Response> single = AsyncHttpSingle.create(
 *     client.prepareGet("http://www.example.com")
 * );
 * single.subscribe(
 *     response -> System.out.println("Status: " + response.getStatusCode()),
 *     error -> System.err.println("Error: " + error)
 * );
 *
 * // Single with custom AsyncHandler
 * Single<String> customSingle = AsyncHttpSingle.create(
 *     client.prepareGet("http://www.example.com"),
 *     () -> new AsyncCompletionHandler<String>() {
 *         public String onCompleted(Response response) {
 *             return response.getResponseBody();
 *         }
 *     }
 * );
 * }</pre>
 *
 * @see <a href="https://github.com/ReactiveX/RxJava">https://github.com/
 * ReactiveX/RxJava</a>
 */
public final class AsyncHttpSingle {

  private AsyncHttpSingle() {
    throw new AssertionError("No instances for you!");
  }

  /**
   * Creates a Single that emits the HTTP response from the given request builder.
   * <p>
   * The request is executed when the Single is subscribed to, using a default
   * AsyncCompletionHandlerBase to handle the response.
   *
   * @param builder the request builder used to execute the HTTP request
   * @return a Single that executes the request on subscription and emits the response
   * @throws NullPointerException if {@code builder} is {@code null}
   */
  public static Single<Response> create(BoundRequestBuilder builder) {
    requireNonNull(builder);
    return create(builder::execute, AsyncCompletionHandlerBase::new);
  }

  /**
   * Creates a Single that emits HTTP responses using a request template function.
   * <p>
   * This overload provides more control over request execution by accepting a function
   * that receives an AsyncHandler and returns a Future. The Future is used for
   * cancellation support when the Single is unsubscribed.
   *
   * @param requestTemplate a function called to start the HTTP request with an
   *                        AsyncHandler that builds the HTTP response and
   *                        propagates results to the returned Single. The
   *                        Future returned by this function is used to cancel
   *                        the request when the Single is unsubscribed
   * @return a Single that executes new requests on subscription by
   * calling the request template and emits the response
   * @throws NullPointerException if {@code requestTemplate} is {@code null}
   */
  public static Single<Response> create(Func1<? super AsyncHandler<?>, ? extends Future<?>> requestTemplate) {
    return create(requestTemplate, AsyncCompletionHandlerBase::new);
  }

  /**
   * Creates a Single that emits results from a custom AsyncHandler.
   * <p>
   * This method allows you to provide a custom handler supplier that produces
   * AsyncHandler instances for processing the HTTP response. The handler's result
   * type determines the Single's emission type.
   *
   * @param builder the request builder used to execute the HTTP request
   * @param handlerSupplier a function that supplies AsyncHandler instances
   *                        to process the HTTP response and produce results
   * @param <T> the type of result produced by the AsyncHandler
   * @return a Single that executes the request on subscription and emits
   * the result produced by the supplied AsyncHandler
   * @throws NullPointerException if at least one of the parameters is {@code null}
   */
  public static <T> Single<T> create(BoundRequestBuilder builder, Func0<? extends AsyncHandler<? extends T>> handlerSupplier) {
    requireNonNull(builder);
    return create(builder::execute, handlerSupplier);
  }

  /**
   * Creates a Single using both a request template and a custom handler supplier.
   * <p>
   * This is the most flexible creation method, allowing full control over both
   * request execution and response handling. The request template provides control
   * over how the request is executed and cancelled, while the handler supplier
   * determines how the response is processed.
   *
   * @param requestTemplate a function called to start the HTTP request with an
   *                        AsyncHandler that builds the HTTP response and
   *                        propagates results to the returned Single. The
   *                        Future returned by this function is used to cancel
   *                        the request when the Single is unsubscribed
   * @param handlerSupplier a function that supplies AsyncHandler instances
   *                        to process the HTTP response and produce results
   * @param <T> the type of result produced by the AsyncHandler
   * @return a Single that executes new requests on subscription by
   * calling the request template and emits the results produced by
   * the supplied AsyncHandler
   * @throws NullPointerException if at least one of the parameters is {@code null}
   */
  public static <T> Single<T> create(Func1<? super AsyncHandler<?>, ? extends Future<?>> requestTemplate,
                                     Func0<? extends AsyncHandler<? extends T>> handlerSupplier) {

    requireNonNull(requestTemplate);
    requireNonNull(handlerSupplier);

    return Single.create(subscriber -> {
      final AsyncHandler<?> bridge = createBridge(subscriber, handlerSupplier.call());
      final Future<?> responseFuture = requestTemplate.call(bridge);
      subscriber.add(Subscriptions.from(responseFuture));
    });
  }

  static <T> AsyncHandler<?> createBridge(SingleSubscriber<? super T> subscriber, AsyncHandler<? extends T> handler) {

    if (handler instanceof ProgressAsyncHandler) {
      return new ProgressAsyncSingleSubscriberBridge<>(subscriber, (ProgressAsyncHandler<? extends T>) handler);
    }

    return new AsyncSingleSubscriberBridge<>(subscriber, handler);
  }
}
