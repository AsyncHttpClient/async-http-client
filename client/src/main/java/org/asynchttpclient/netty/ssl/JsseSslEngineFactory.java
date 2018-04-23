/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.ssl;

import org.asynchttpclient.AsyncHttpClientConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

public class JsseSslEngineFactory extends SslEngineFactoryBase {

  private final SSLContext sslContext;

  public JsseSslEngineFactory(SSLContext sslContext) {
    this.sslContext = sslContext;
  }

  @Override
  public SSLEngine newSslEngine(AsyncHttpClientConfig config, String peerHost, int peerPort) {
    SSLEngine sslEngine = sslContext.createSSLEngine(domain(peerHost), peerPort);
    configureSslEngine(sslEngine, config);
    return sslEngine;
  }
}
