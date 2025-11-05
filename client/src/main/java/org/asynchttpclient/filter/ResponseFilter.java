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
 * Filter interface for preprocessing responses before they are processed by the client.
 * Response filters are invoked after receiving the response but before authorization,
 * proxy authentication, redirect handling, and {@link org.asynchttpclient.AsyncHandler}
 * invocation.
 *
 * <p>Response filters can be used to:</p>
 * <ul>
 *   <li>Inspect response status codes and headers</li>
 *   <li>Implement custom retry logic</li>
 *   <li>Trigger request replay for specific conditions</li>
 *   <li>Log or audit responses</li>
 *   <li>Wrap or replace the {@link org.asynchttpclient.AsyncHandler}</li>
 *   <li>Abort response processing by throwing {@link FilterException}</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Retry on 503 Service Unavailable
 * public class RetryOnServiceUnavailableFilter implements ResponseFilter {
 *     @Override
 *     public <T> FilterContext<T> filter(FilterContext<T> ctx) throws FilterException {
 *         HttpResponseStatus status = ctx.getResponseStatus();
 *         if (status.getStatusCode() == 503) {
 *             // Trigger request replay
 *             return new FilterContext.FilterContextBuilder<>(ctx)
 *                 .replayRequest(true)
 *                 .build();
 *         }
 *         return ctx;
 *     }
 * }
 *
 * // Register the filter
 * AsyncHttpClient client = Dsl.asyncHttpClient(
 *     new DefaultAsyncHttpClientConfig.Builder()
 *         .addResponseFilter(new RetryOnServiceUnavailableFilter())
 *         .build()
 * );
 * }</pre>
 */
public interface ResponseFilter {

  /**
   * Processes the response before it is handled by the client.
   * The {@link org.asynchttpclient.AsyncHttpClient} will use the returned {@link FilterContext}
   * to determine if response processing should continue. If {@link FilterContext#replayRequest()}
   * returns true, a new request will be made using {@link FilterContext#getRequest()} and the
   * current response processing will be aborted.
   *
   * @param ctx the filter context containing the response status, headers, and handler
   * @param <T> the handler result type
   * @return a {@link FilterContext}, which may be the same as or different from the input context
   * @throws FilterException to abort the response processing and stop filter chain execution
   */
  <T> FilterContext<T> filter(FilterContext<T> ctx) throws FilterException;
}
