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
package com.ning.http.client;

import com.ning.http.util.SslUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import java.security.GeneralSecurityException;

/**
 * Factory that creates an {@link SSLEngine} to be used for a single SSL connection.
 */
public interface SSLEngineFactory {

    /**
     * Creates new {@link SSLEngine}.
     *
     * @return new engine
     * @throws GeneralSecurityException if the SSLEngine cannot be created
     */
    SSLEngine newSSLEngine(String peerHost, int peerPort) throws GeneralSecurityException;

    public static class DefaultSSLEngineFactory implements SSLEngineFactory {

        private final AsyncHttpClientConfig config;

        public DefaultSSLEngineFactory(AsyncHttpClientConfig config) {
            this.config = config;
        }

        @Override
        public SSLEngine newSSLEngine(String peerHost, int peerPort) throws GeneralSecurityException {
            SSLContext sslContext = config.getSSLContext();

            if (sslContext == null)
                sslContext = SslUtils.getInstance().getSSLContext(config.isAcceptAnyCertificate());

            SSLEngine sslEngine = sslContext.createSSLEngine(peerHost, peerPort);
            sslEngine.setUseClientMode(true);

            if (config.getEnabledProtocols() != null)
                sslEngine.setEnabledProtocols(config.getEnabledProtocols());

            if (config.getEnabledCipherSuites() != null)
                sslEngine.setEnabledCipherSuites(config.getEnabledCipherSuites());

            return sslEngine;
        }
    }
}
