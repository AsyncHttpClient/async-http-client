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
 *
 * @see <a href="https://github.com/ReactiveX/RxJava">https://github.com/
 * ReactiveX/RxJava</a>
 */
public final class AsyncHttpSingle {

  private AsyncHttpSingle() {
    throw new AssertionError("No instances for you!");
  }

  /**
   * Emits the responses to HTTP requests obtained from {@code builder}.
   *
   * @param builder used to build the HTTP request that is to be executed
   * @return a {@code Single} that executes new requests on subscription
   * obtained from {@code builder} on subscription and that emits the
   * response
   * @throws NullPointerException if {@code builder} is {@code null}
   */
  public static Single<Response> create(BoundRequestBuilder builder) {
    requireNonNull(builder);
    return create(builder::execute, AsyncCompletionHandlerBase::new);
  }

  /**
   * Emits the responses to HTTP requests obtained by calling
   * {@code requestTemplate}.
   *
   * @param requestTemplate called to start the HTTP request with an
   *                        {@code AysncHandler} that builds the HTTP response and
   *                        propagates results to the returned {@code Single}. The
   *                        {@code Future} that is returned by {@code requestTemplate}
   *                        will be used to cancel the request when the {@code Single} is
   *                        unsubscribed.
   * @return a {@code Single} that executes new requests on subscription by
   * calling {@code requestTemplate} and that emits the response
   * @throws NullPointerException if {@code requestTemplate} is {@code null}
   */
  public static Single<Response> create(Func1<? super AsyncHandler<?>, ? extends Future<?>> requestTemplate) {
    return create(requestTemplate, AsyncCompletionHandlerBase::new);
  }

  /**
   * Emits the results of {@code AsyncHandlers} obtained from
   * {@code handlerSupplier} for HTTP requests obtained from {@code builder}.
   *
   * @param builder         used to build the HTTP request that is to be executed
   * @param handlerSupplier supplies the desired {@code AsyncHandler}
   *                        instances that are used to produce results
   * @return a {@code Single} that executes new requests on subscription
   * obtained from {@code builder} and that emits the result of the
   * {@code AsyncHandler} obtained from {@code handlerSupplier}
   * @throws NullPointerException if at least one of the parameters is
   *                              {@code null}
   */
  public static <T> Single<T> create(BoundRequestBuilder builder, Func0<? extends AsyncHandler<? extends T>> handlerSupplier) {
    requireNonNull(builder);
    return create(builder::execute, handlerSupplier);
  }

  /**
   * Emits the results of {@code AsyncHandlers} obtained from
   * {@code handlerSupplier} for HTTP requests obtained obtained by calling
   * {@code requestTemplate}.
   *
   * @param requestTemplate called to start the HTTP request with an
   *                        {@code AysncHandler} that builds the HTTP response and
   *                        propagates results to the returned {@code Single}.  The
   *                        {@code Future} that is returned by {@code requestTemplate}
   *                        will be used to cancel the request when the {@code Single} is
   *                        unsubscribed.
   * @param handlerSupplier supplies the desired {@code AsyncHandler}
   *                        instances that are used to produce results
   * @return a {@code Single} that executes new requests on subscription by
   * calling {@code requestTemplate} and that emits the results
   * produced by the {@code AsyncHandlers} supplied by
   * {@code handlerSupplier}
   * @throws NullPointerException if at least one of the parameters is
   *                              {@code null}
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
