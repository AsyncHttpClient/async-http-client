/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

public final class Assertions {

    private Assertions() {
    }

    @Contract(value = "null, _ -> fail", pure = true)
    public static <T> T assertNotNull(@Nullable T value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        return value;

    }

    @Contract(value = "null, _ -> fail", pure = true)
    public static String assertNotEmpty(@Nullable String value, String name) {
        assertNotNull(value, name);
        if (value.length() == 0) {
            throw new IllegalArgumentException("empty " + name);
        }
        return value;
    }
}
