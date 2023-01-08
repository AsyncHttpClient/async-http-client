/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.ssl;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.SslEngineFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

public abstract class SslEngineFactoryBase implements SslEngineFactory {

    protected String domain(String hostname) {
        int fqdnLength = hostname.length() - 1;
        return hostname.charAt(fqdnLength) == '.' ? hostname.substring(0, fqdnLength) : hostname;
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
