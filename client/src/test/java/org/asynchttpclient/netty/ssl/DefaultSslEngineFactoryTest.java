/*
 *    Copyright (c) 2014-2026 AsyncHttpClient Project. All rights reserved.
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
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLEngine;
import java.util.Arrays;
import java.util.List;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the ALPN advertised by {@link DefaultSslEngineFactory}. A WebSocket connection
 * ({@code http2Allowed = false}) must advertise only {@code http/1.1}, so a server cannot negotiate
 * {@code h2} for a {@code wss://} request — AsyncHttpClient does not implement RFC 8441 (WebSocket over
 * HTTP/2). This is the root-cause guard for the Issue #2160 WebSocket-over-HTTP/2 corruption: without it
 * the server could select {@code h2}, the handshake would be written as a plain HTTP/2 request, and the
 * connection would be (mis)pooled in the HTTP/2 registry.
 */
public class DefaultSslEngineFactoryTest {

    private static List<String> alpnProtocols(boolean http2Allowed) {
        AsyncHttpClientConfig config = config().setHttp2Enabled(true).build();
        DefaultSslEngineFactory factory = new DefaultSslEngineFactory();
        try {
            factory.init(config);
            SSLEngine engine = factory.newSslEngine(config, "localhost", 443, http2Allowed);
            return Arrays.asList(engine.getSSLParameters().getApplicationProtocols());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            factory.destroy();
        }
    }

    @Test
    public void webSocketEngineAdvertisesOnlyHttp11() {
        List<String> protocols = alpnProtocols(false);
        assertFalse(protocols.contains("h2"),
                "h2 must NOT be offered for WebSocket connections (no RFC 8441 support); got " + protocols);
        assertEquals(Arrays.asList("http/1.1"), protocols,
                "a WebSocket connection must advertise only http/1.1 so the server cannot select HTTP/2");
    }

    @Test
    public void regularEngineAdvertisesH2ThenHttp11() {
        List<String> protocols = alpnProtocols(true);
        assertEquals(Arrays.asList("h2", "http/1.1"), protocols,
                "a non-WebSocket HTTPS connection must still offer h2 then http/1.1");
    }

    @Test
    public void threeArgNewSslEngineRemainsHttp2Capable() {
        // The pre-existing 3-arg newSslEngine (no http2Allowed flag) must keep advertising h2 for
        // backwards compatibility with custom callers / SslEngineFactory subclasses.
        AsyncHttpClientConfig config = config().setHttp2Enabled(true).build();
        DefaultSslEngineFactory factory = new DefaultSslEngineFactory();
        try {
            factory.init(config);
            SSLEngine engine = factory.newSslEngine(config, "localhost", 443);
            assertTrue(Arrays.asList(engine.getSSLParameters().getApplicationProtocols()).contains("h2"),
                    "the 3-arg newSslEngine must keep advertising h2");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            factory.destroy();
        }
    }
}
