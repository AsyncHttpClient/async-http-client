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
package org.asynchttpclient.filter;

/**
 * Filter interface for handling IOExceptions that occur during HTTP transactions.
 * This filter is invoked when an {@link java.io.IOException} is thrown during
 * request execution, allowing custom error handling and recovery logic.
 *
 * <p>IOException filters can be used to:</p>
 * <ul>
 *   <li>Implement custom retry logic for network failures</li>
 *   <li>Log or monitor connection errors</li>
 *   <li>Trigger request replay for transient failures</li>
 *   <li>Provide fallback responses</li>
 *   <li>Abort request processing by throwing {@link FilterException}</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Retry on connection timeout
 * public class RetryOnTimeoutFilter implements IOExceptionFilter {
 *     private final int maxRetries = 3;
 *     private final AtomicInteger retryCount = new AtomicInteger(0);
 *
 *     @Override
 *     public <T> FilterContext<T> filter(FilterContext<T> ctx) throws FilterException {
 *         IOException exception = ctx.getIOException();
 *         if (exception instanceof SocketTimeoutException && retryCount.incrementAndGet() < maxRetries) {
 *             // Replay the request
 *             return new FilterContext.FilterContextBuilder<>(ctx)
 *                 .replayRequest(true)
 *                 .build();
 *         }
 *         // Max retries exceeded, propagate the exception
 *         throw new FilterException("Max retries exceeded", exception);
 *     }
 * }
 *
 * // Register the filter
 * AsyncHttpClient client = Dsl.asyncHttpClient(
 *     new DefaultAsyncHttpClientConfig.Builder()
 *         .addIOExceptionFilter(new RetryOnTimeoutFilter())
 *         .build()
 * );
 * }</pre>
 */
public interface IOExceptionFilter {

  /**
   * Processes the IOException that occurred during request execution.
   * The {@link org.asynchttpclient.AsyncHttpClient} will use the returned {@link FilterContext}
   * to determine whether to replay the {@link org.asynchttpclient.Request} or abort processing.
   * If {@link FilterContext#replayRequest()} returns true, the request will be retried.
   *
   * @param ctx the filter context containing the exception and request details
   * @param <T> the handler result type
   * @return a {@link FilterContext}, which may be the same as or different from the input context
   * @throws FilterException to abort the request processing and stop filter chain execution
   */
  <T> FilterContext<T> filter(FilterContext<T> ctx) throws FilterException;
}
