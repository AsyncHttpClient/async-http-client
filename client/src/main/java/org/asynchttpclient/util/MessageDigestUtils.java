/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class for managing pooled MessageDigest instances.
 * <p>
 * This class provides thread-local pools of MessageDigest instances for commonly used
 * hashing algorithms (MD5 and SHA-1). Using pooled instances improves performance by
 * avoiding the overhead of repeatedly creating new MessageDigest instances.
 * </p>
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

  /**
   * Returns a thread-local, reset MD5 MessageDigest instance.
   * <p>
   * The returned MessageDigest is reset and ready for use. Each thread gets its own
   * instance, making this method thread-safe without requiring synchronization.
   * </p>
   *
   * <p><b>Usage Examples:</b></p>
   * <pre>{@code
   * MessageDigest md5 = pooledMd5MessageDigest();
   * md5.update(data);
   * byte[] hash = md5.digest();
   * }</pre>
   *
   * @return a reset MD5 MessageDigest instance
   * @throws InternalError if MD5 algorithm is not supported on this platform
   */
  public static MessageDigest pooledMd5MessageDigest() {
    MessageDigest md = MD5_MESSAGE_DIGESTS.get();
    md.reset();
    return md;
  }

  /**
   * Returns a thread-local, reset SHA-1 MessageDigest instance.
   * <p>
   * The returned MessageDigest is reset and ready for use. Each thread gets its own
   * instance, making this method thread-safe without requiring synchronization.
   * </p>
   *
   * <p><b>Usage Examples:</b></p>
   * <pre>{@code
   * MessageDigest sha1 = pooledSha1MessageDigest();
   * sha1.update(data);
   * byte[] hash = sha1.digest();
   * }</pre>
   *
   * @return a reset SHA-1 MessageDigest instance
   * @throws InternalError if SHA-1 algorithm is not supported on this platform
   */
  public static MessageDigest pooledSha1MessageDigest() {
    MessageDigest md = SHA1_MESSAGE_DIGESTS.get();
    md.reset();
    return md;
  }
}
