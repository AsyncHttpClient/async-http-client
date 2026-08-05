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
package org.asynchttpclient;

import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.asynchttpclient.Dsl.digestAuthRealm;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * RFC 7616 section 3.3 requires the client nonce to be unpredictable: it is what stops a hostile or
 * compromised server from choosing the whole digest input and precomputing responses. A non-cryptographic
 * PRNG such as {@link java.util.concurrent.ThreadLocalRandom} does not provide that, so the cnonce must be
 * drawn from a {@link SecureRandom}.
 */
public class DigestCnonceTest {

  /**
   * Structural check: whatever generator {@code Realm.Builder} holds for the cnonce, it must be a
   * {@link SecureRandom}. Deliberately does not hard-code the field name so a rename does not break it.
   */
  @Test
  public void cnonceIsDrawnFromASecureRandom() throws Exception {
    SecureRandom found = null;
    for (Field field : Realm.Builder.class.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      field.setAccessible(true);
      Object value = field.get(null);
      if (value instanceof SecureRandom) {
        found = (SecureRandom) value;
      } else if (value instanceof ThreadLocal) {
        Object supplied = ((ThreadLocal<?>) value).get();
        if (supplied instanceof SecureRandom) {
          found = (SecureRandom) supplied;
        }
      }
    }

    assertNotNull(found, "Realm.Builder must draw the Digest cnonce from a SecureRandom (RFC 7616 section 3.3)");
  }

  @Test
  public void everyDigestRealmGetsItsOwnCnonce() {
    Set<String> cnonces = new HashSet<>();
    for (int i = 0; i < 64; i++) {
      Realm realm = digestAuthRealm("user", "password")
              .setRealmName("realm")
              .setNonce("aabbccddeeff")
              .setQop("auth")
              .build();
      assertNotNull(realm.getCnonce(), "a Digest realm built against a server nonce must carry a cnonce");
      cnonces.add(realm.getCnonce());
    }

    assertTrue(cnonces.size() == 64, "each Digest realm must get a fresh cnonce, got " + cnonces.size() + " distinct out of 64");
  }
}
