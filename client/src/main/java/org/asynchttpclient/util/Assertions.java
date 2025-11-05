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
 * Utility class for performing common assertion checks on method parameters.
 * <p>
 * This class provides convenience methods to validate that parameters meet certain requirements
 * such as being non-null or non-empty. These methods throw appropriate exceptions when assertions fail.
 * </p>
 */
public final class Assertions {

  private Assertions() {
  }

  /**
   * Asserts that the specified value is not null.
   * <p>
   * This method validates that the provided value is not null and returns the value if valid.
   * If the value is null, a {@link NullPointerException} is thrown with the parameter name.
   * </p>
   *
   * <p><b>Usage Examples:</b></p>
   * <pre>{@code
   * String userName = assertNotNull(getUserName(), "userName");
   * Request request = assertNotNull(buildRequest(), "request");
   * }</pre>
   *
   * @param <T>   the type of the value being checked
   * @param value the value to check for null
   * @param name  the name of the parameter (used in error message)
   * @return the non-null value
   * @throws NullPointerException if the value is null
   */
  public static <T> T assertNotNull(T value, String name) {
    if (value == null)
      throw new NullPointerException(name);
    return value;

  }

  /**
   * Asserts that the specified string is not null and not empty.
   * <p>
   * This method validates that the provided string is not null and has a length greater than zero.
   * If the value is null, a {@link NullPointerException} is thrown. If the value is an empty string,
   * an {@link IllegalArgumentException} is thrown.
   * </p>
   *
   * <p><b>Usage Examples:</b></p>
   * <pre>{@code
   * String host = assertNotEmpty(uri.getHost(), "host");
   * String userName = assertNotEmpty(credentials.getUsername(), "userName");
   * }</pre>
   *
   * @param value the string value to check
   * @param name  the name of the parameter (used in error messages)
   * @return the non-empty string value
   * @throws NullPointerException     if the value is null
   * @throws IllegalArgumentException if the value is an empty string
   */
  public static String assertNotEmpty(String value, String name) {
    assertNotNull(value, name);
    if (value.length() == 0)
      throw new IllegalArgumentException("empty " + name);
    return value;
  }
}
