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
 *
 */
package com.ning.http.client.oauth;

import com.ning.http.util.UTF8Codec;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Since cloning (of MAC instances)  is not necessarily supported on all platforms
 * (and specifically seems to fail on MacOS), let's wrap synchronization/reuse details here.
 * Assumption is that this is bit more efficient (even considering synchronization)
 * than locating and reconstructing instance each time.
 * In future we may want to use soft references and thread local instance.
 *
 * @author tatu (tatu.saloranta@iki.fi)
 */
public class ThreadSafeHMAC {
    private static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";

    private final Mac mac;

    public ThreadSafeHMAC(ConsumerKey consumerAuth, RequestToken userAuth) {
        byte[] keyBytes = UTF8Codec.toUTF8(consumerAuth.getSecret() + "&" + userAuth.getSecret());
        SecretKeySpec signingKey = new SecretKeySpec(keyBytes, HMAC_SHA1_ALGORITHM);

        // Get an hmac_sha1 instance and initialize with the signing key
        try {
            mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
            mac.init(signingKey);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }

    }

    public synchronized byte[] digest(byte[] message) {
        mac.reset();
        return mac.doFinal(message);
    }
}
