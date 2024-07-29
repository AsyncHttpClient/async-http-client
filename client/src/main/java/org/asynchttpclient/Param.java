/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A pair of (name, value) String
 *
 * @author slandelle
 */
public class Param {

    private final String name;
    private final @Nullable String value;

    public Param(String name, @Nullable String value) {
        this.name = name;
        this.value = value;
    }

    public static @Nullable List<Param> map2ParamList(Map<String, List<String>> map) {
        if (map == null) {
            return null;
        }

        List<Param> params = new ArrayList<>(map.size());
        for (Map.Entry<String, List<String>> entries : map.entrySet()) {
            String name = entries.getKey();
            for (String value : entries.getValue()) {
                params.add(new Param(name, value));
            }
        }
        return params;
    }

    public String getName() {
        return name;
    }

    public @Nullable String getValue() {
        return value;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (name == null ? 0 : name.hashCode());
        result = prime * result + (value == null ? 0 : value.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof Param)) {
            return false;
        }
        Param other = (Param) obj;
        if (name == null) {
            if (other.name != null) {
                return false;
            }
        } else if (!name.equals(other.name)) {
            return false;
        }
        if (value == null) {
            return other.value == null;
        } else {
            return value.equals(other.value);
        }
    }
}
