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
 * Helper class for sorting query and form parameters that we need
 */
final class Parameter implements Comparable<Parameter> {

  final String key, value;

  Parameter(String key, String value) {
    this.key = key;
    this.value = value;
  }

  @Override
  public int compareTo(Parameter other) {
    int keyDiff = key.compareTo(other.key);
    return keyDiff == 0 ? value.compareTo(other.value) : keyDiff;
  }

  @Override
  public String toString() {
    return key + "=" + value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    Parameter parameter = (Parameter) o;
    return key.equals(parameter.key) && value.equals(parameter.value);
  }

  @Override
  public int hashCode() {
    int result = key.hashCode();
    result = 31 * result + value.hashCode();
    return result;
  }
}
