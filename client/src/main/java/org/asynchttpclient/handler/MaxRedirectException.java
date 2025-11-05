/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.handler;

/**
 * Exception thrown when the maximum number of redirects has been exceeded.
 * <p>
 * This exception is thrown when a request follows more HTTP redirects than the limit
 * configured via {@link org.asynchttpclient.DefaultAsyncHttpClientConfig#getMaxRedirects()}.
 * The default maximum is typically 5 redirects, but can be configured per client.
 * <p>
 * This prevents infinite redirect loops and excessive redirect chains that could
 * indicate a misconfigured server or a malicious redirect attack.
 *
 * <p><b>Example:</b></p>
 * <pre>{@code
 * AsyncHttpClient client = new DefaultAsyncHttpClient(
 *     new DefaultAsyncHttpClientConfig.Builder()
 *         .setMaxRedirects(3)
 *         .build()
 * );
 *
 * try {
 *     client.prepareGet("http://example.com/redirect-loop").execute().get();
 * } catch (ExecutionException e) {
 *     if (e.getCause() instanceof MaxRedirectException) {
 *         // Handle too many redirects
 *     }
 * }
 * }</pre>
 */
public class MaxRedirectException extends Exception {
  private static final long serialVersionUID = 1L;

  /**
   * Creates a new MaxRedirectException with the specified message.
   * <p>
   * The exception is created with suppression and stack trace writing disabled
   * for performance reasons, as this is typically a well-understood control flow exception.
   *
   * @param msg the detail message explaining why the maximum redirect limit was exceeded
   */
  public MaxRedirectException(String msg) {
    super(msg, null, true, false);
  }
}
