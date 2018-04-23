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

final class Parameters {

  private List<Parameter> parameters = new ArrayList<>();

  public Parameters add(String key, String value) {
    parameters.add(new Parameter(key, value));
    return this;
  }

  public void reset() {
    parameters.clear();
  }

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
