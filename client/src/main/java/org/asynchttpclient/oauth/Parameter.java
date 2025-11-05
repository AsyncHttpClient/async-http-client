/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.oauth;

/**
 * Helper class for sorting query and form parameters used in OAuth signature calculation.
 * <p>
 * This class represents a key-value pair that can be sorted according to OAuth specifications.
 * Parameters are first sorted by key, then by value if keys are equal.
 * </p>
 */
final class Parameter implements Comparable<Parameter> {

  final String key, value;

  /**
   * Creates a new parameter with the specified key and value.
   *
   * @param key the parameter key
   * @param value the parameter value
   */
  public Parameter(String key, String value) {
    this.key = key;
    this.value = value;
  }

  /**
   * Compares this parameter to another for sorting purposes.
   * <p>
   * Parameters are compared first by key, then by value if keys are equal.
   * This ordering is required for OAuth signature calculation.
   * </p>
   *
   * @param other the parameter to compare to
   * @return a negative integer, zero, or a positive integer as this parameter
   *         is less than, equal to, or greater than the specified parameter
   */
  @Override
  public int compareTo(Parameter other) {
    int keyDiff = key.compareTo(other.key);
    return keyDiff == 0 ? value.compareTo(other.value) : keyDiff;
  }

  /**
   * Returns a string representation of this parameter in the form "key=value".
   *
   * @return a string representation of this parameter
   */
  @Override
  public String toString() {
    return key + "=" + value;
  }

  /**
   * Indicates whether some other object is equal to this parameter.
   * <p>
   * Two parameters are considered equal if both their keys and values are equal.
   * </p>
   *
   * @param o the reference object with which to compare
   * @return {@code true} if this parameter is equal to the argument; {@code false} otherwise
   */
  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    Parameter parameter = (Parameter) o;
    return key.equals(parameter.key) && value.equals(parameter.value);
  }

  /**
   * Returns a hash code value for this parameter.
   *
   * @return a hash code value for this parameter
   */
  @Override
  public int hashCode() {
    int result = key.hashCode();
    result = 31 * result + value.hashCode();
    return result;
  }
}
