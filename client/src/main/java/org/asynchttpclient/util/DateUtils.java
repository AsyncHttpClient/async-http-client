/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.util;

/**
 * Utility class for date and time operations.
 * <p>
 * This class provides convenience methods for working with time-related operations
 * in the async-http-client library.
 * </p>
 */
public final class DateUtils {

  private DateUtils() {
  }

  /**
   * Returns the current time in milliseconds.
   * <p>
   * This method is a wrapper around {@link System#currentTimeMillis()} and provides
   * the current time in milliseconds since the Unix epoch (January 1, 1970, 00:00:00 GMT).
   * The name "unprecise" indicates that the precision may vary depending on the underlying
   * operating system.
   * </p>
   *
   * <p><b>Usage Examples:</b></p>
   * <pre>{@code
   * long startTime = unpreciseMillisTime();
   * // perform operation
   * long elapsed = unpreciseMillisTime() - startTime;
   * }</pre>
   *
   * @return the current time in milliseconds
   */
  public static long unpreciseMillisTime() {
    return System.currentTimeMillis();
  }
}
