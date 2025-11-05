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
 * Filter interface for preprocessing requests before they are sent to the remote server.
 * Request filters are executed in the order they are added to the client configuration.
 *
 * <p>Request filters can be used to:</p>
 * <ul>
 *   <li>Add, modify, or remove request headers</li>
 *   <li>Modify request parameters or body</li>
 *   <li>Implement custom authentication schemes</li>
 *   <li>Log or audit requests</li>
 *   <li>Wrap or replace the {@link org.asynchttpclient.AsyncHandler}</li>
 *   <li>Abort requests by throwing {@link FilterException}</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Add an authentication header to all requests
 * public class AuthFilter implements RequestFilter {
 *     @Override
 *     public <T> FilterContext<T> filter(FilterContext<T> ctx) throws FilterException {
 *         Request originalRequest = ctx.getRequest();
 *         Request newRequest = new RequestBuilder(originalRequest)
 *             .addHeader("Authorization", "Bearer " + getToken())
 *             .build();
 *         return new FilterContext.FilterContextBuilder<>(ctx)
 *             .request(newRequest)
 *             .build();
 *     }
 * }
 *
 * // Register the filter
 * AsyncHttpClient client = Dsl.asyncHttpClient(
 *     new DefaultAsyncHttpClientConfig.Builder()
 *         .addRequestFilter(new AuthFilter())
 *         .build()
 * );
 * }</pre>
 */
public interface RequestFilter {

  /**
   * Processes the request before it is sent to the remote server.
   * The {@link org.asynchttpclient.AsyncHttpClient} will use the returned {@link FilterContext}
   * to obtain the (potentially modified) {@link FilterContext#getRequest()} and
   * {@link FilterContext#getAsyncHandler()} for continuing the request processing.
   *
   * @param ctx the filter context containing the request and handler
   * @param <T> the handler result type
   * @return a {@link FilterContext}, which may be the same as or different from the input context
   * @throws FilterException to abort the request and stop filter chain processing
   */
  <T> FilterContext<T> filter(FilterContext<T> ctx) throws FilterException;
}
