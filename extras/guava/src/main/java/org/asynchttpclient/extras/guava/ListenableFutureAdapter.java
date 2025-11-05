/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.extras.guava;

import org.asynchttpclient.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Adapter utility to convert AsyncHttpClient's ListenableFuture to Guava's ListenableFuture.
 * <p>
 * This adapter allows seamless integration between AsyncHttpClient and Guava's utilities
 * for working with futures and callbacks.
 */
public final class ListenableFutureAdapter {

  /**
   * Converts an AsyncHttpClient ListenableFuture to a Guava ListenableFuture.
   * <p>
   * The returned future delegates all operations to the original AsyncHttpClient future,
   * preserving cancellation, execution, and listener behavior.
   *
   * <p><b>Usage Examples:</b></p>
   * <pre>{@code
   * AsyncHttpClient client = asyncHttpClient();
   * ListenableFuture<Response> ahcFuture = client.prepareGet("http://www.example.com").execute();
   * com.google.common.util.concurrent.ListenableFuture<Response> guavaFuture =
   *     ListenableFutureAdapter.asGuavaFuture(ahcFuture);
   *
   * // Now you can use Guava utilities
   * Futures.addCallback(guavaFuture, new FutureCallback<Response>() {
   *     public void onSuccess(Response response) {
   *         System.out.println("Status: " + response.getStatusCode());
   *     }
   *     public void onFailure(Throwable thrown) {
   *         System.err.println("Request failed: " + thrown);
   *     }
   * }, MoreExecutors.directExecutor());
   * }</pre>
   *
   * @param future an AsyncHttpClient ListenableFuture to adapt
   * @param <V>    the type of the future's result value
   * @return a Guava ListenableFuture that delegates to the provided future
   */
  public static <V> com.google.common.util.concurrent.ListenableFuture<V> asGuavaFuture(final ListenableFuture<V> future) {

    return new com.google.common.util.concurrent.ListenableFuture<V>() {

      public boolean cancel(boolean mayInterruptIfRunning) {
        return future.cancel(mayInterruptIfRunning);
      }

      public V get() throws InterruptedException, ExecutionException {
        return future.get();
      }

      public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(timeout, unit);
      }

      public boolean isCancelled() {
        return future.isCancelled();
      }

      public boolean isDone() {
        return future.isDone();
      }

      public void addListener(final Runnable runnable, final Executor executor) {
        future.addListener(runnable, executor);
      }
    };
  }
}
