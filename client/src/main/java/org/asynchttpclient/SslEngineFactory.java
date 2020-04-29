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
package org.asynchttpclient;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

public interface SslEngineFactory {

  /**
   * Creates a new {@link SSLEngine}.
   *
   * @param config   the client config
   * @param peerHost the peer hostname
   * @param peerPort the peer port
   * @return new engine
   */
  SSLEngine newSslEngine(AsyncHttpClientConfig config, String peerHost, int peerPort);

  /**
   * Perform any necessary one-time configuration. This will be called just once before {@code newSslEngine} is called
   * for the first time.
   *
   * @param config the client config
   * @throws SSLException if initialization fails. If an exception is thrown, the instance will not be used as client
   *                      creation will fail.
   */
  default void init(AsyncHttpClientConfig config) throws SSLException {
    // no op
  }
}
