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
package org.asynchttpclient.util;

public final class ThrowableUtil {

    private ThrowableUtil() {
        // Prevent outside initialization
    }

    /**
     * @param <T>    the Throwable type
     * @param t      the throwable whose stacktrace we want to remove
     * @param clazz  the caller class
     * @param method the caller method
     * @return the input throwable with removed stacktrace
     */
    public static <T extends Throwable> T unknownStackTrace(T t, Class<?> clazz, String method) {
        t.setStackTrace(new StackTraceElement[]{new StackTraceElement(clazz.getName(), method, null, -1)});
        return t;
    }
}
