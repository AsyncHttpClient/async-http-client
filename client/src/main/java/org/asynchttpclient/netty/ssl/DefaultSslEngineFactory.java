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

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.asynchttpclient.AsyncHttpClientConfig;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.util.Arrays;

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

public class DefaultSslEngineFactory extends SslEngineFactoryBase {

  private volatile SslContext sslContext;

  private SslContext buildSslContext(AsyncHttpClientConfig config) throws SSLException {
    if (config.getSslContext() != null) {
      return config.getSslContext();
    }

    SslContextBuilder sslContextBuilder = SslContextBuilder.forClient()//
            .sslProvider(config.isUseOpenSsl() ? SslProvider.OPENSSL : SslProvider.JDK)//
            .sessionCacheSize(config.getSslSessionCacheSize())//
            .sessionTimeout(config.getSslSessionTimeout());

    if (isNonEmpty(config.getEnabledProtocols())) {
      sslContextBuilder.protocols(config.getEnabledProtocols());
    }

    if (isNonEmpty(config.getEnabledCipherSuites())) {
      sslContextBuilder.ciphers(Arrays.asList(config.getEnabledCipherSuites()));
    }

    if (config.isUseInsecureTrustManager()) {
      sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
    }

    return configureSslContextBuilder(sslContextBuilder).build();
  }

  @Override
  public SSLEngine newSslEngine(AsyncHttpClientConfig config, String peerHost, int peerPort) {
    // FIXME should be using ctx allocator
    SSLEngine sslEngine = sslContext.newEngine(ByteBufAllocator.DEFAULT, peerHost, peerPort);
    configureSslEngine(sslEngine, config);
    return sslEngine;
  }

  @Override
  public void init(AsyncHttpClientConfig config) throws SSLException {
    sslContext = buildSslContext(config);
  }

  /**
   * The last step of configuring the SslContextBuilder used to create an SslContext when no context is provided in the {@link AsyncHttpClientConfig}. This defaults to no-op and
   * is intended to be overridden as needed.
   *
   * @param builder builder with normal configuration applied
   * @return builder to be used to build context (can be the same object as the input)
   */
  protected SslContextBuilder configureSslContextBuilder(SslContextBuilder builder) {
    // default to no op
    return builder;
  }
}
