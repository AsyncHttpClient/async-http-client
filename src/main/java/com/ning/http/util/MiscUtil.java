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
package com.ning.http.util;

import java.util.Collection;
import java.util.Map;

public class MiscUtil {

    private MiscUtil() {
    }

    public static boolean isNonEmpty(String string) {
        return string != null && string.length() != 0;
    }

    public static boolean isNonEmpty(Object[] array) {
        return array != null && array.length != 0;
    }

    public static boolean isNonEmpty(byte[] array) {
        return array != null && array.length != 0;
    }

    public static boolean isNonEmpty(Collection<?> collection) {
        return collection != null && !collection.isEmpty();
    }

    public static boolean isNonEmpty(Map<?, ?> map) {
        return map != null && !map.isEmpty();
    }

    public static boolean getBoolean(String systemPropName, boolean defaultValue) {
        String systemPropValue = System.getProperty(systemPropName);
        return systemPropValue != null ? systemPropValue.equalsIgnoreCase("true") : defaultValue;
    }
}
