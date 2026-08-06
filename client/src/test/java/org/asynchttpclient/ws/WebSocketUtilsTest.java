/*
 * Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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

import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class WebSocketUtilsTest {

  /**
   * RFC 6455 section 10.3: "the nonce MUST be selected randomly for each connection" from a source that
   * "cannot be guessed", because Sec-WebSocket-Accept is a pure function of the key. An off-path party who
   * can predict the key can precompute a valid accept value, so the handshake check stops proving the 101
   * came from a peer that saw the request. Netty's ThreadLocalRandom is a 48-bit LCG.
   */
  @Test
  public void webSocketKeyComesFromASecureGenerator() throws Exception {
    Field field = WebSocketUtils.class.getDeclaredField("KEY_RANDOM");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    ThreadLocal<Random> holder = (ThreadLocal<Random>) field.get(null);
    assertTrue(holder.get() instanceof SecureRandom,
            "the Sec-WebSocket-Key generator must be a SecureRandom but was: " + holder.get().getClass());
  }

  @Test
  public void webSocketKeyIsSixteenFreshBytes() {
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      String key = WebSocketUtils.getWebSocketKey();
      assertEquals(Base64.getDecoder().decode(key).length, 16, "RFC 6455 requires a 16-byte nonce: " + key);
      assertTrue(keys.add(key), "Sec-WebSocket-Key was generated twice: " + key);
    }
  }

  @Test
  public void acceptKeyMatchesTheRfcExample() {
    // RFC 6455 section 1.3.
    assertEquals(WebSocketUtils.getAcceptKey("dGhlIHNhbXBsZSBub25jZQ=="), "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");
  }
}
