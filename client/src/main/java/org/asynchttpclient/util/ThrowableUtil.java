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

public final class ThrowableUtil {

  private ThrowableUtil() {
  }

  /**
   * @param <T>    the Throwable type
   * @param t      the throwable whose stacktrace we want to remove
   * @param clazz  the caller class
   * @param method the caller method
   * @return the input throwable with removed stacktrace
   */
  public static <T extends Throwable> T unknownStackTrace(T t, Class<?> clazz, String method) {
    t.setStackTrace(new StackTraceElement[]{new StackTraceElement(clazz.getName(), method, null, -1)});
    return t;
  }
}
