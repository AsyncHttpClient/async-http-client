/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.ws;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.asynchttpclient.util.Base64;

public final class WebSocketUtils {
    public static final String MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static String getKey() {
        byte[] nonce = createRandomBytes(16);
        return Base64.encode(nonce);
    }

    public static String getAcceptKey(String key) throws UnsupportedEncodingException {
        String acceptSeed = key + MAGIC_GUID;
        byte[] sha1 = sha1(acceptSeed.getBytes(US_ASCII));
        return Base64.encode(sha1);
    }

    public static byte[] md5(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("MD5 not supported on this platform");
        }
    }

    public static byte[] sha1(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            return md.digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("SHA-1 not supported on this platform");
        }
    }

    public static byte[] createRandomBytes(int size) {
        byte[] bytes = new byte[size];

        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) createRandomNumber(0, 255);
        }

        return bytes;
    }

    public static int createRandomNumber(int min, int max) {
        return (int) (Math.random() * max + min);
    }

}
