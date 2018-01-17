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

public final class ListenableFutureAdapter {

  /**
   * @param future an AHC ListenableFuture
   * @param <V>    the Future's value type
   * @return a Guava ListenableFuture
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
