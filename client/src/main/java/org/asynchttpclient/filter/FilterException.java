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
 * Exception that can be thrown by filters to interrupt filter chain processing
 * and abort the current request or response handling.
 *
 * <p>When a filter throws this exception:</p>
 * <ul>
 *   <li>The filter chain execution is immediately stopped</li>
 *   <li>No subsequent filters in the chain are invoked</li>
 *   <li>The request or response processing is terminated</li>
 *   <li>The exception is propagated to the {@link org.asynchttpclient.AsyncHandler}</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Abort request if authentication token is missing
 * public <T> FilterContext<T> filter(FilterContext<T> ctx) throws FilterException {
 *     if (ctx.getRequest().getHeaders().get("Authorization") == null) {
 *         throw new FilterException("Missing authentication token");
 *     }
 *     return ctx;
 * }
 *
 * // Abort on too many retries
 * if (retryCount > maxRetries) {
 *     throw new FilterException("Maximum retry count exceeded", originalException);
 * }
 * }</pre>
 */
@SuppressWarnings("serial")
public class FilterException extends Exception {

  /**
   * Constructs a new filter exception with the specified detail message.
   *
   * @param message the detail message
   */
  public FilterException(final String message) {
    super(message);
  }

  /**
   * Constructs a new filter exception with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause the cause of this exception
   */
  public FilterException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
