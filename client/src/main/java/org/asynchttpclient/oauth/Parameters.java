/*
 *    Copyright (c) 2017-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.oauth;

import org.asynchttpclient.util.StringBuilderPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Parameters {

    private final List<Parameter> parameters = new ArrayList<>();

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
