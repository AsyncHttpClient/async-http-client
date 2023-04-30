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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;

public final class MiscUtils {

    private MiscUtils() {
        // Prevent outside initialization
    }

    // NullAway is not powerful enough to recognise that if the values has passed the check, it's not null
    @Contract(value = "null -> false", pure = true)
    public static boolean isNonEmpty(@Nullable String string) {
        return !isEmpty(string);
    }

    public static boolean isEmpty(@Nullable String string) {
        return string == null || string.isEmpty();
    }

    public static boolean isNonEmpty(@Nullable Object[] array) {
        return array != null && array.length != 0;
    }

    public static boolean isNonEmpty(byte @Nullable [] array) {
        return array != null && array.length != 0;
    }

    public static boolean isNonEmpty(@Nullable Collection<?> collection) {
        return collection != null && !collection.isEmpty();
    }

    public static boolean isNonEmpty(@Nullable Map<?, ?> map) {
        return map != null && !map.isEmpty();
    }

    public static <T> T withDefault(@Nullable T value, T def) {
        return value == null ? def : value;
    }

    public static void closeSilently(@Nullable Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                //
            }
        }
    }

    public static Throwable getCause(Throwable t) {
        Throwable cause = t.getCause();
        return cause != null ? getCause(cause) : t;
    }
}
