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

import java.util.Base64;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.asynchttpclient.util.MessageDigestUtils.pooledSha1MessageDigest;

/**
 * Utility class for WebSocket protocol operations.
 * <p>
 * This class provides helper methods for WebSocket handshake key generation and validation
 * according to RFC 6455 (The WebSocket Protocol). It handles the cryptographic operations
 * required for the WebSocket opening handshake.
 * </p>
 */
public final class WebSocketUtils {
  /**
   * Magic GUID defined in RFC 6455 for WebSocket handshake accept key calculation.
   */
  private static final String MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  /**
   * Generates a random WebSocket key for the client handshake.
   * <p>
   * This method creates a Base64-encoded random 16-byte value to be sent in the
   * Sec-WebSocket-Key header during the WebSocket opening handshake. The server
   * will use this key to generate the Sec-WebSocket-Accept response header.
   * </p>
   *
   * @return a Base64-encoded random key suitable for WebSocket handshake
   */
  public static String getWebSocketKey() {
    byte[] nonce = new byte[16];
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (int i = 0; i < nonce.length; i++) {
      nonce[i] = (byte) random.nextInt(256);
    }
    return Base64.getEncoder().encodeToString(nonce);
  }

  /**
   * Computes the WebSocket accept key from a client key.
   * <p>
   * This method implements the algorithm defined in RFC 6455 for computing the
   * Sec-WebSocket-Accept header value. It concatenates the client key with the
   * magic GUID, computes SHA-1 hash, and Base64-encodes the result.
   * </p>
   * <p>
   * This is used by clients to validate server responses and by servers to
   * generate the accept header.
   * </p>
   *
   * @param key the Sec-WebSocket-Key value from the client handshake
   * @return the Base64-encoded accept key for the Sec-WebSocket-Accept header
   */
  public static String getAcceptKey(String key) {
    return Base64.getEncoder().encodeToString(pooledSha1MessageDigest().digest(
              (key + MAGIC_GUID).getBytes(US_ASCII)));
  }
}
