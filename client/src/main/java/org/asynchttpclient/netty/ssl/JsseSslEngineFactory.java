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
