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

    private MessageDigestUtils() {
        // Prevent outside initialization
    }

    public static MessageDigest pooledMd5MessageDigest() {
        MessageDigest md = MD5_MESSAGE_DIGESTS.get();
        md.reset();
        return md;
    }

    public static MessageDigest pooledSha1MessageDigest() {
        MessageDigest md = SHA1_MESSAGE_DIGESTS.get();
        md.reset();
        return md;
    }
}
