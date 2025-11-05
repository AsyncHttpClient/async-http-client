/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.util;

/**
 * Utility methods for working with Throwable instances.
 * <p>
 * This class provides helper methods for manipulating exceptions and their stack traces,
 * particularly useful for performance optimization in high-throughput scenarios.
 * </p>
 */
public final class ThrowableUtil {

  private ThrowableUtil() {
  }

  /**
   * Replaces the stack trace of a throwable with a minimal, synthetic stack trace.
   * <p>
   * This method is useful for pre-allocated or frequently thrown exceptions where
   * the overhead of capturing a full stack trace is undesirable. The resulting
   * throwable has a single stack trace element indicating the caller class and method.
   * </p>
   * <p>
   * Note: Use this method with caution, as it removes valuable debugging information.
   * It's primarily intended for performance-critical paths where exceptions are expected
   * and stack traces are not needed.
   * </p>
   *
   * <p><b>Usage Examples:</b></p>
   * <pre>{@code
   * IOException exception = unknownStackTrace(
   *     new IOException("Connection closed"),
   *     MyClass.class,
   *     "processRequest"
   * );
   * }</pre>
   *
   * @param <T>    the Throwable type
   * @param t      the throwable whose stack trace should be replaced
   * @param clazz  the caller class to record in the synthetic stack trace
   * @param method the caller method to record in the synthetic stack trace
   * @return the input throwable with a minimal synthetic stack trace
   */
  public static <T extends Throwable> T unknownStackTrace(T t, Class<?> clazz, String method) {
    t.setStackTrace(new StackTraceElement[]{new StackTraceElement(clazz.getName(), method, null, -1)});
    return t;
  }
}
