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

import io.netty.util.internal.ThreadLocalRandom;
import org.asynchttpclient.util.Base64;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.asynchttpclient.util.MessageDigestUtils.pooledSha1MessageDigest;

public final class WebSocketUtils {
  private static final String MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  public static String getWebSocketKey() {
    byte[] nonce = new byte[16];
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (int i = 0; i < nonce.length; i++) {
      nonce[i] = (byte) random.nextInt(256);
    }
    return Base64.encode(nonce);
  }

  public static String getAcceptKey(String key) {
    return Base64.encode(pooledSha1MessageDigest().digest((key + MAGIC_GUID).getBytes(US_ASCII)));
  }
}
