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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Thread-safety: Each digest is kept in a ThreadLocal. This
 * class is intended for use on long-lived threads (e.g., Netty event loops).
 * If you call it from a short-lived or unbounded thread pool, you may
 * inadvertently retain one MessageDigest instance per thread, leading
 * to memory leaks.
 */
public final class MessageDigestUtils {

    private static final ThreadLocal<MessageDigest> MD5_MESSAGE_DIGESTS = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("MD5 not supported on this platform");
        }
    });

    private static final ThreadLocal<MessageDigest> SHA1_MESSAGE_DIGESTS = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("SHA1 not supported on this platform");
        }
    });

    private static final ThreadLocal<MessageDigest> SHA256_MESSAGE_DIGESTS = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("SHA-256 not supported on this platform");
        }
    });

    private static final ThreadLocal<MessageDigest> SHA512_256_MESSAGE_DIGESTS = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-512/256");
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("SHA-512/256 not supported on this platform");
        }
    });

    private MessageDigestUtils() {
        // Prevent outside initialization
    }

    /**
     * Returns a pooled MessageDigest instance for the given algorithm name.
     * Supported: "MD5", "SHA-1", "SHA-256", "SHA-512/256" (and aliases).
     * The returned instance is thread-local and reset before use.
     *
     * @param algorithm the algorithm name (e.g., "MD5", "SHA-256", "SHA-512/256")
     * @return a reset MessageDigest instance for the algorithm
     * @throws IllegalArgumentException if the algorithm is not supported
     */
    public static MessageDigest pooledMessageDigest(String algorithm) {
        String alg = algorithm.replace("_", "-").toUpperCase();
        MessageDigest md;
        if ("SHA-512-256".equals(alg)) alg = "SHA-512/256";
        switch (alg) {
            case "MD5":
                md = MD5_MESSAGE_DIGESTS.get();
                break;
            case "SHA1":
            case "SHA-1":
                md = SHA1_MESSAGE_DIGESTS.get();
                break;
            case "SHA-256":
                md = SHA256_MESSAGE_DIGESTS.get();
                break;
            case "SHA-512/256":
                md = SHA512_256_MESSAGE_DIGESTS.get();
                break;
            default:
                try {
                    md = MessageDigest.getInstance(algorithm);
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalArgumentException("Unsupported digest algorithm: " + algorithm, e);
                }
        }
        md.reset();
        return md;
    }

    /**
     * Converts a byte array to a lower-case hexadecimal String.
     * Locale-safe and allocation-free except for the final char[] → String copy.
     *
     * @param bytes the byte array to convert (must not be null)
     * @return 2×length lower-case hex string
     * @throws IllegalArgumentException if {@code bytes} is null
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes == null");
        }
        final char[] HEX = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length << 1];

        for (int i = 0, j = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[j++] = HEX[v >>> 4];
            out[j++] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * @return a pooled, reset MessageDigest for MD5
     */
    public static MessageDigest pooledMd5MessageDigest() {
        return pooledMessageDigest("MD5");
    }

    /**
     * @return a pooled, reset MessageDigest for SHA-1
     */
    public static MessageDigest pooledSha1MessageDigest() {
        return pooledMessageDigest("SHA-1");
    }

    /**
     * @return a pooled, reset MessageDigest for SHA-256
     */
    public static MessageDigest pooledSha256MessageDigest() {
        return pooledMessageDigest("SHA-256");
    }

    /**
     * @return a pooled, reset MessageDigest for SHA-512/256
     */
    public static MessageDigest pooledSha512_256MessageDigest() {
        return pooledMessageDigest("SHA-512/256");
    }
}
