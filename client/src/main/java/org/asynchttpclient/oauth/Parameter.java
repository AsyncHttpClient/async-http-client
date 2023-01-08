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
        return key + '=' + value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

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
