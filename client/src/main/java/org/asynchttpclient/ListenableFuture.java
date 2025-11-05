/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
/*
 * Copyright (C) 2007 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asynchttpclient;

import java.util.concurrent.*;

/**
 * An extended {@link Future} that provides additional control and listener capabilities.
 * <p>
 * This interface extends the standard Java {@link Future} with async-http-client specific
 * functionality including the ability to abort requests, add completion listeners, and
 * convert to {@link CompletableFuture}.
 * </p>
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * AsyncHttpClient client = Dsl.asyncHttpClient();
 * ListenableFuture<Response> future = client.prepareGet("http://example.com").execute();
 *
 * // Add a listener for completion
 * future.addListener(() -> System.out.println("Request completed"),
 *     Executors.newSingleThreadExecutor());
 *
 * // Convert to CompletableFuture for modern async APIs
 * CompletableFuture<Response> cf = future.toCompletableFuture();
 *
 * // Block and get the result
 * Response response = future.get();
 * }</pre>
 *
 * @param <V> the type of value that will be returned
 * @see Future
 * @see CompletableFuture
 */
public interface ListenableFuture<V> extends Future<V> {

  /**
   * Marks this future as done and releases any internal locks.
   * This should be called when the operation completes successfully without exceptions.
   */
  void done();

  /**
   * Aborts the current operation and propagates the exception to the {@link AsyncHandler} or {@link Future}.
   * This will cause {@link #get()} to throw an {@link java.util.concurrent.ExecutionException}.
   *
   * @param t the exception that caused the abort
   */
  void abort(Throwable t);

  /**
   * Touches this future to prevent timeout.
   * This is useful for long-running operations where you want to signal progress
   * without completing the future.
   */
  void touch();

  /**
   * Adds a listener to be executed when this future completes.
   * <p>
   * The listener will be passed to the executor for execution when the future's
   * computation is {@linkplain Future#isDone() complete}. If the executor is null,
   * the listener will be executed in the thread that completes the future.
   * </p>
   * <p>
   * There is no guaranteed ordering of listener execution. Listeners may be called
   * in the order they were added or out of order, but all listeners are guaranteed
   * to be called once the computation completes.
   * </p>
   *
   * @param listener the listener to run when the computation is complete
   * @param exec the executor to run the listener in, or null to run in the completing thread
   * @return this future instance for method chaining
   */
  ListenableFuture<V> addListener(Runnable listener, Executor exec);

  /**
   * Converts this ListenableFuture to a {@link CompletableFuture}.
   * This allows integration with Java 8+ async APIs and functional composition.
   *
   * @return a CompletableFuture that completes with the same result or exception as this future
   */
  CompletableFuture<V> toCompletableFuture();

  class CompletedFailure<T> implements ListenableFuture<T> {

    private final ExecutionException e;

    public CompletedFailure(Throwable t) {
      e = new ExecutionException(t);
    }

    public CompletedFailure(String message, Throwable t) {
      e = new ExecutionException(message, t);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return true;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return true;
    }

    @Override
    public T get() throws ExecutionException {
      throw e;
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws ExecutionException {
      throw e;
    }

    @Override
    public void done() {
    }

    @Override
    public void abort(Throwable t) {
    }

    @Override
    public void touch() {
    }

    @Override
    public ListenableFuture<T> addListener(Runnable listener, Executor exec) {
      if (exec != null) {
        exec.execute(listener);
      } else {
        listener.run();
      }
      return this;
    }

    @Override
    public CompletableFuture<T> toCompletableFuture() {
      CompletableFuture<T> future = new CompletableFuture<>();
      future.completeExceptionally(e);
      return future;
    }
  }
}
