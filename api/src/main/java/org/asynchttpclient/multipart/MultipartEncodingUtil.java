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
package org.asynchttpclient.multipart;

import java.nio.charset.Charset;

/**
 * This class is an adaptation of the Apache HttpClient implementation
 *
 * @link http://hc.apache.org/httpclient-3.x/
 */
public class MultipartEncodingUtil {

    public static byte[] getAsciiBytes(String data) {
        return data.getBytes(Charset.forName("US-ASCII"));
    }

    public static String getAsciiString(final byte[] data) {
        if (data == null) {
            throw new NullPointerException("data");
        }

        return new String(data, Charset.forName("US-ASCII"));
    }

    public static byte[] getBytes(final String data, String charset) {

        if (data == null) {
            throw new NullPointerException("data");
        }

        if (charset == null || charset.length() == 0) {
            throw new NullPointerException("charset");
        }

        return data.getBytes(Charset.forName(charset));
    }
}
