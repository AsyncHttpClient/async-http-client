/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.util;

import java.io.UnsupportedEncodingException;

/**
 * Wrapper class for more convenient (and possibly more efficient in future)
 * UTF-8 encoding and decoding.
 */
public class UTF8Codec {
    private final static String ENCODING_UTF8 = "UTF-8";

    // Until we target JDK6+
    public static byte[] toUTF8(String input) {
        try {
            return input.getBytes(ENCODING_UTF8);
        } catch (UnsupportedEncodingException e) { // never happens, but since it's declared...
            throw new IllegalStateException();
        }
    }
}
