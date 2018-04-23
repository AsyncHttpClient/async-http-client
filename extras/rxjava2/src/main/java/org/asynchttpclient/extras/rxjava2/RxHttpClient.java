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
 *
 * @see <a href="https://github.com/ReactiveX/RxJava">RxJava – Reactive Extensions for the JVM</a>
 */
public interface RxHttpClient {

  /**
   * Returns a new {@code RxHttpClient} instance that uses the given {@code asyncHttpClient} under the hoods.
   *
   * @param asyncHttpClient the Async HTTP Client instance to be used
   * @return a new {@code RxHttpClient} instance
   * @throws NullPointerException if {@code asyncHttpClient} is {@code null}
   */
  static RxHttpClient create(AsyncHttpClient asyncHttpClient) {
    return new DefaultRxHttpClient(asyncHttpClient);
  }

  /**
   * Prepares the given {@code request}. For each subscription to the returned {@code Maybe}, a new HTTP request will
   * be executed and its response will be emitted.
   *
   * @param request the request that is to be executed
   * @return a {@code Maybe} that executes {@code request} upon subscription and emits the response
   * @throws NullPointerException if {@code request} is {@code null}
   */
  default Maybe<Response> prepare(Request request) {
    return prepare(request, AsyncCompletionHandlerBase::new);
  }

  /**
   * Prepares the given {@code request}. For each subscription to the returned {@code Maybe}, a new HTTP request will
   * be executed and the results of {@code AsyncHandlers} obtained from {@code handlerSupplier} will be emitted.
   *
   * @param <T>             the result type produced by handlers produced by {@code handlerSupplier} and emitted by the returned
   *                        {@code Maybe} instance
   * @param request         the request that is to be executed
   * @param handlerSupplier supplies the desired {@code AsyncHandler} instances that are used to produce results
   * @return a {@code Maybe} that executes {@code request} upon subscription and that emits the results produced by
   * the supplied handers
   * @throws NullPointerException if at least one of the parameters is {@code null}
   */
  <T> Maybe<T> prepare(Request request, Supplier<? extends AsyncHandler<T>> handlerSupplier);
}
