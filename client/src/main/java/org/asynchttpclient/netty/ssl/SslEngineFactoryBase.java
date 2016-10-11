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

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.SslEngineFactory;

public abstract class SslEngineFactoryBase implements SslEngineFactory {

    protected void configureSslEngine(SSLEngine sslEngine, AsyncHttpClientConfig config) {
        sslEngine.setUseClientMode(true);
        SSLParameters params = sslEngine.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        sslEngine.setSSLParameters(params);

        if (isNonEmpty(config.getEnabledProtocols()))
            sslEngine.setEnabledProtocols(config.getEnabledProtocols());

        if (isNonEmpty(config.getEnabledCipherSuites()))
            sslEngine.setEnabledCipherSuites(config.getEnabledCipherSuites());
    }
}
