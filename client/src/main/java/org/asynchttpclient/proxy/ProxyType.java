/*
 * Copyright (c) 2018 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.proxy;

/**
 * Enumeration of supported proxy server types.
 * <p>
 * This enum defines the types of proxy protocols that can be used for routing
 * HTTP requests through a proxy server. Each type has different characteristics
 * and capabilities.
 * </p>
 */
public enum ProxyType {
  /**
   * HTTP proxy type.
   * <p>
   * HTTP proxies operate at the application layer (Layer 7) and understand HTTP protocol.
   * They can cache responses, modify headers, and provide content filtering.
   * This is the most common proxy type for web traffic.
   * </p>
   */
  HTTP(true),

  /**
   * SOCKS version 4 proxy type.
   * <p>
   * SOCKS v4 is a circuit-level proxy that operates at the session layer (Layer 5).
   * It supports TCP connections but does not support authentication or UDP.
   * </p>
   */
  SOCKS_V4(false),

  /**
   * SOCKS version 5 proxy type.
   * <p>
   * SOCKS v5 is an enhanced version of SOCKS that supports authentication,
   * UDP traffic, and IPv6. It provides more features than SOCKS v4 while
   * still operating at the session layer.
   * </p>
   */
  SOCKS_V5(false);

  private final boolean http;

  /**
   * Creates a proxy type with the specified HTTP flag.
   *
   * @param http true if this is an HTTP proxy type, false for SOCKS
   */
  ProxyType(boolean http) {
    this.http = http;
  }

  /**
   * Checks whether this is an HTTP proxy type.
   *
   * @return true if this is an HTTP proxy, false otherwise
   */
  public boolean isHttp() {
    return http;
  }

  /**
   * Checks whether this is a SOCKS proxy type.
   *
   * @return true if this is a SOCKS proxy (v4 or v5), false otherwise
   */
  public boolean isSocks() {
    return !isHttp();
  }
}
