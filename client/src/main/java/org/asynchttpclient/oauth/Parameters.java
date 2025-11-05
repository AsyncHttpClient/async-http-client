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

import org.asynchttpclient.util.StringBuilderPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collection of OAuth parameters used for signature calculation.
 * <p>
 * This class maintains a list of parameters that can be sorted and concatenated
 * according to OAuth 1.0 specifications. Parameters should be added in their
 * percent-encoded form.
 * </p>
 */
final class Parameters {

  private List<Parameter> parameters = new ArrayList<>();

  /**
   * Adds a parameter to the collection.
   *
   * @param key the parameter key (should be percent-encoded)
   * @param value the parameter value (should be percent-encoded)
   * @return this Parameters instance for method chaining
   */
  public Parameters add(String key, String value) {
    parameters.add(new Parameter(key, value));
    return this;
  }

  /**
   * Clears all parameters from the collection.
   * <p>
   * This method allows the Parameters instance to be reused for multiple signature calculations.
   * </p>
   */
  public void reset() {
    parameters.clear();
  }

  /**
   * Sorts the parameters and concatenates them into a query string.
   * <p>
   * Parameters are sorted according to OAuth specifications (first by key, then by value),
   * then concatenated with '&' separators. The result is used in OAuth signature base string
   * construction.
   * </p>
   *
   * @return the sorted and concatenated parameter string
   */
  String sortAndConcat() {
    // then sort them (AFTER encoding, important)
    Collections.sort(parameters);

    // and build parameter section using pre-encoded pieces:
    StringBuilder encodedParams = StringBuilderPool.DEFAULT.stringBuilder();
    for (Parameter param : parameters) {
      encodedParams.append(param.key).append('=').append(param.value).append('&');
    }
    int length = encodedParams.length();
    if (length > 0) {
      encodedParams.setLength(length - 1);
    }
    return encodedParams.toString();
  }
}
