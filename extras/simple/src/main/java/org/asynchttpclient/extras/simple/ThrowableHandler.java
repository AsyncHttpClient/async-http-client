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

package org.asynchttpclient.extras.simple;


/**
 * Simple {@link Throwable} handler to be used with {@link SimpleAsyncHttpClient}.
 * <p>
 * This interface provides a callback mechanism for handling exceptions that occur
 * during HTTP request execution, allowing custom error handling logic.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * ThrowableHandler handler = throwable -> {
 *     logger.error("Request failed: " + throwable.getMessage(), throwable);
 *     // Custom error handling logic
 * };
 *
 * SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
 *     .setDefaultThrowableHandler(handler)
 *     .setUrl("http://www.example.com")
 *     .build();
 * }</pre>
 */
public interface ThrowableHandler {

  /**
   * Handles a throwable that occurred during HTTP request processing.
   *
   * @param t the throwable that was thrown during request execution
   */
  void onThrowable(Throwable t);
}
