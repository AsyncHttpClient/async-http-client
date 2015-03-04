/*
 * Copyright (c) 2015 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client.providers.grizzly;

import com.ning.http.client.SSLEngineFactory;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLEngine;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;

/**
 * Grizzly {@link SSLEngineConfigurator} based on AHC {@link SSLEngineFactory}.
 */
final class AhcSSLEngineConfigurator extends SSLEngineConfigurator {
    private final SSLEngineFactory ahcSslEngineFactory;

    public AhcSSLEngineConfigurator(final SSLEngineFactory ahcSslEngineFactory) {
        this.ahcSslEngineFactory = ahcSslEngineFactory;
    }
    
    @Override
    public SSLEngineConfigurator copy() {
        return new AhcSSLEngineConfigurator(ahcSslEngineFactory);
    }

    @Override
    public SSLEngine configure(SSLEngine sslEngine) {
        return sslEngine;
    }

    @Override
    public SSLEngine createSSLEngine() {
        return createSSLEngine(null, -1);
    }

    @Override
    public SSLEngine createSSLEngine(String peerHost, int peerPort) {
        try {
            return ahcSslEngineFactory.newSSLEngine(peerHost, peerPort);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
