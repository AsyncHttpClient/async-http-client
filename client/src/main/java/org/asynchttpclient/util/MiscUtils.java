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
package org.asynchttpclient.util;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * Miscellaneous utility methods for common operations.
 * <p>
 * This class provides helper methods for checking emptiness of various data structures,
 * handling default values, closing resources, and working with exceptions.
 * </p>
 */
public class MiscUtils {

  private MiscUtils() {
  }

  /**
   * Checks if a string is non-empty (not null and has length greater than zero).
   *
   * @param string the string to check
   * @return true if the string is non-empty, false otherwise
   */
  public static boolean isNonEmpty(String string) {
    return !isEmpty(string);
  }

  /**
   * Checks if a string is empty (null or has zero length).
   *
   * @param string the string to check
   * @return true if the string is empty or null, false otherwise
   */
  public static boolean isEmpty(String string) {
    return string == null || string.isEmpty();
  }

  /**
   * Checks if an object array is non-empty (not null and has length greater than zero).
   *
   * @param array the array to check
   * @return true if the array is non-empty, false otherwise
   */
  public static boolean isNonEmpty(Object[] array) {
    return array != null && array.length != 0;
  }

  /**
   * Checks if a byte array is non-empty (not null and has length greater than zero).
   *
   * @param array the array to check
   * @return true if the array is non-empty, false otherwise
   */
  public static boolean isNonEmpty(byte[] array) {
    return array != null && array.length != 0;
  }

  /**
   * Checks if a collection is non-empty (not null and contains at least one element).
   *
   * @param collection the collection to check
   * @return true if the collection is non-empty, false otherwise
   */
  public static boolean isNonEmpty(Collection<?> collection) {
    return collection != null && !collection.isEmpty();
  }

  /**
   * Checks if a map is non-empty (not null and contains at least one entry).
   *
   * @param map the map to check
   * @return true if the map is non-empty, false otherwise
   */
  public static boolean isNonEmpty(Map<?, ?> map) {
    return map != null && !map.isEmpty();
  }

  /**
   * Returns the value if non-null, otherwise returns the default value.
   *
   * <p><b>Usage Examples:</b></p>
   * <pre>{@code
   * String result = withDefault(optionalValue, "defaultValue");
   * Integer timeout = withDefault(config.getTimeout(), 30000);
   * }</pre>
   *
   * @param <T>   the type of the value
   * @param value the value to check
   * @param def   the default value to return if value is null
   * @return the value if non-null, otherwise the default value
   */
  public static <T> T withDefault(T value, T def) {
    return value == null ? def : value;
  }

  /**
   * Closes a Closeable resource silently, suppressing any IOException.
   * <p>
   * This method is useful for cleanup operations where exceptions should not
   * interrupt the flow of execution.
   * </p>
   *
   * @param closeable the resource to close (may be null)
   */
  public static void closeSilently(Closeable closeable) {
    if (closeable != null)
      try {
        closeable.close();
      } catch (IOException e) {
        //
      }
  }

  /**
   * Recursively retrieves the root cause of a throwable.
   * <p>
   * Traverses the exception chain to find the deepest cause. If the throwable
   * has no cause, returns the throwable itself.
   * </p>
   *
   * @param t the throwable to analyze
   * @return the root cause throwable
   */
  public static Throwable getCause(Throwable t) {
    Throwable cause = t.getCause();
    return cause != null ? getCause(cause) : t;
  }
}
