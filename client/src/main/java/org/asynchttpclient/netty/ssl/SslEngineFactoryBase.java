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
import org.asynchttpclient.SslEngineFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

public abstract class SslEngineFactoryBase implements SslEngineFactory {

  protected String domain(String hostname) {
    int fqdnLength = hostname.length() - 1;
    return hostname.charAt(fqdnLength) == '.' ?
            hostname.substring(0, fqdnLength) :
            hostname;
  }

  protected void configureSslEngine(SSLEngine sslEngine, AsyncHttpClientConfig config) {
    sslEngine.setUseClientMode(true);
    if (!config.isDisableHttpsEndpointIdentificationAlgorithm()) {
      SSLParameters params = sslEngine.getSSLParameters();
      params.setEndpointIdentificationAlgorithm("HTTPS");
      sslEngine.setSSLParameters(params);
    }
  }
}
