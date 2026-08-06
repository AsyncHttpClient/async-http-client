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

import java.security.SecureRandom;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.asynchttpclient.util.MessageDigestUtils.pooledSha1MessageDigest;

public final class WebSocketUtils {
  private static final String MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  // RFC 6455 section 10.3 requires the handshake nonce to come from a strong source of entropy: it is
  // what proves the 101 was produced by a peer that saw this request. Netty's ThreadLocalRandom is a
  // 48-bit LCG, so an off-path party who can guess the key can precompute a valid Sec-WebSocket-Accept.
  private static final ThreadLocal<SecureRandom> KEY_RANDOM = ThreadLocal.withInitial(SecureRandom::new);

  public static String getWebSocketKey() {
    byte[] nonce = new byte[16];
    KEY_RANDOM.get().nextBytes(nonce);
    return Base64.getEncoder().encodeToString(nonce);
  }

  public static String getAcceptKey(String key) {
    return Base64.getEncoder().encodeToString(pooledSha1MessageDigest().digest(
              (key + MAGIC_GUID).getBytes(US_ASCII)));
  }
}
