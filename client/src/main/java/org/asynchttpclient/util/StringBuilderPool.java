/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.util;

/**
 * A thread-local pool of StringBuilder instances for efficient string building.
 * <p>
 * This class maintains a thread-local pool of StringBuilder instances with an initial
 * capacity of 512 characters. Using pooled StringBuilders reduces object allocation
 * overhead for frequently performed string concatenation operations.
 * </p>
 */
public class StringBuilderPool {

  /**
   * The default, shared StringBuilderPool instance.
   */
  public static final StringBuilderPool DEFAULT = new StringBuilderPool();

  private final ThreadLocal<StringBuilder> pool = ThreadLocal.withInitial(() -> new StringBuilder(512));

  /**
   * Returns a reset, pooled StringBuilder ready for use.
   * <p>
   * The returned StringBuilder has its length reset to zero but retains its underlying
   * character array capacity. Each thread gets its own instance, making this method
   * thread-safe without requiring synchronization.
   * </p>
   * <p><b>IMPORTANT:</b> The returned StringBuilder must not be appended to itself,
   * as it's reused across calls within the same thread.
   * </p>
   *
   * <p><b>Usage Examples:</b></p>
   * <pre>{@code
   * StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
   * sb.append("hello").append(" ").append("world");
   * String result = sb.toString();
   * }</pre>
   *
   * @return a reset, pooled StringBuilder
   */
  public StringBuilder stringBuilder() {
    StringBuilder sb = pool.get();
    sb.setLength(0);
    return sb;
  }
}
