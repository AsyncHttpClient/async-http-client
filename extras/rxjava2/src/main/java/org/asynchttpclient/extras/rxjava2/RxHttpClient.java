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
import org.asynchttpclient.*;

import java.util.function.Supplier;

/**
 * Prepares HTTP requests by wrapping them into RxJava 2 {@code Maybe} instances.
 * <p>
 * This interface provides a reactive API for executing HTTP requests using RxJava 2's
 * Maybe type, which can emit zero or one result. Each subscription to the returned
 * Maybe triggers a new HTTP request execution.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create an RxHttpClient
 * AsyncHttpClient client = asyncHttpClient();
 * RxHttpClient rxClient = RxHttpClient.create(client);
 *
 * // Simple GET request
 * Request request = get("http://www.example.com").build();
 * rxClient.prepare(request)
 *     .subscribe(
 *         response -> System.out.println("Status: " + response.getStatusCode()),
 *         error -> System.err.println("Failed: " + error)
 *     );
 *
 * // Chain with RxJava operators
 * rxClient.prepare(request)
 *     .map(Response::getResponseBody)
 *     .filter(body -> body.contains("success"))
 *     .subscribe(body -> System.out.println("Body: " + body));
 * }</pre>
 *
 * @see <a href="https://github.com/ReactiveX/RxJava">RxJava – Reactive Extensions for the JVM</a>
 */
public interface RxHttpClient {

  /**
   * Creates a new RxHttpClient instance that delegates to the given AsyncHttpClient.
   *
   * @param asyncHttpClient the Async HTTP Client instance to be used for executing requests
   * @return a new RxHttpClient instance
   * @throws NullPointerException if {@code asyncHttpClient} is {@code null}
   */
  static RxHttpClient create(AsyncHttpClient asyncHttpClient) {
    return new DefaultRxHttpClient(asyncHttpClient);
  }

  /**
   * Prepares a request for execution, returning a Maybe that emits the response.
   * <p>
   * Each subscription to the returned Maybe triggers a new HTTP request execution.
   * The response is processed using a default AsyncCompletionHandlerBase.
   *
   * @param request the HTTP request to execute
   * @return a Maybe that executes the request upon subscription and emits the response
   * @throws NullPointerException if {@code request} is {@code null}
   */
  default Maybe<Response> prepare(Request request) {
    return prepare(request, AsyncCompletionHandlerBase::new);
  }

  /**
   * Prepares a request for execution with a custom handler supplier.
   * <p>
   * Each subscription to the returned Maybe triggers a new HTTP request execution.
   * The response is processed by an AsyncHandler obtained from the handlerSupplier,
   * and the handler's result is emitted by the Maybe.
   *
   * @param <T>             the result type produced by the handler and emitted by the Maybe
   * @param request         the HTTP request to execute
   * @param handlerSupplier a supplier that provides AsyncHandler instances for processing responses
   * @return a Maybe that executes the request upon subscription and emits the handler's result
   * @throws NullPointerException if at least one of the parameters is {@code null}
   */
  <T> Maybe<T> prepare(Request request, Supplier<? extends AsyncHandler<T>> handlerSupplier);
}
