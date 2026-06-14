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

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import org.asynchttpclient.AsyncHttpClientConfig;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.util.Arrays;

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

public class DefaultSslEngineFactory extends SslEngineFactoryBase {

    private volatile SslContext sslContext;
    // WebSocket connections use a context that advertises only http/1.1 in ALPN: AsyncHttpClient does not
    // implement RFC 8441 (WebSocket over HTTP/2), so the server must not be able to negotiate h2 for them.
    private volatile SslContext http1OnlySslContext;

    private SslContext buildSslContext(AsyncHttpClientConfig config, boolean http2Allowed) throws SSLException {
        if (config.getSslContext() != null) {
            return config.getSslContext();
        }

        SslContextBuilder sslContextBuilder = SslContextBuilder.forClient()
                .sslProvider(config.isUseOpenSsl() ? SslProvider.OPENSSL : SslProvider.JDK)
                .sessionCacheSize(config.getSslSessionCacheSize())
                .sessionTimeout(config.getSslSessionTimeout());

        if (isNonEmpty(config.getEnabledProtocols())) {
            sslContextBuilder.protocols(config.getEnabledProtocols());
        }

        if (isNonEmpty(config.getEnabledCipherSuites())) {
            sslContextBuilder.ciphers(Arrays.asList(config.getEnabledCipherSuites()));
        } else if (!config.isFilterInsecureCipherSuites()) {
            sslContextBuilder.ciphers(null, IdentityCipherSuiteFilter.INSTANCE_DEFAULTING_TO_SUPPORTED_CIPHERS);
        }

        if (config.isUseInsecureTrustManager()) {
            sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        }

        sslContextBuilder.endpointIdentificationAlgorithm(
                config.isDisableHttpsEndpointIdentificationAlgorithm() ? "" : "HTTPS");

        if (config.isHttp2Enabled()) {
            // For a WebSocket connection (http2Allowed=false) advertise only http/1.1, so the server cannot
            // select h2 (which AHC cannot speak for WebSocket — no RFC 8441). Otherwise advertise h2 then http/1.1.
            String[] protocols = http2Allowed
                    ? new String[]{ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1}
                    : new String[]{ApplicationProtocolNames.HTTP_1_1};
            sslContextBuilder.applicationProtocolConfig(new ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    protocols));
        }

        return configureSslContextBuilder(sslContextBuilder).build();
    }

    @Override
    public SSLEngine newSslEngine(AsyncHttpClientConfig config, String peerHost, int peerPort) {
        return newSslEngine(config, peerHost, peerPort, true);
    }

    @Override
    public SSLEngine newSslEngine(AsyncHttpClientConfig config, String peerHost, int peerPort, boolean http2Allowed) {
        SslContext context = http2Allowed ? sslContext : http1OnlySslContext(config);
        SSLEngine sslEngine = config.isDisableHttpsEndpointIdentificationAlgorithm() ?
                context.newEngine(ByteBufAllocator.DEFAULT) :
                context.newEngine(ByteBufAllocator.DEFAULT, domain(peerHost), peerPort);
        configureSslEngine(sslEngine, config);
        return sslEngine;
    }

    /**
     * Returns the context for a WebSocket connection, which must advertise only http/1.1 (AsyncHttpClient does
     * not implement RFC 8441, WebSocket over HTTP/2). Built lazily and cached on first use so a client that
     * never opens a {@code wss://} connection never pays for a second {@link SslContext}.
     * <p>
     * Only a self-built, h2-enabled context advertises h2 and therefore needs a separate http/1.1-only variant;
     * a user-supplied context or an h2-disabled one already negotiates http/1.1, so it is reused (which also
     * avoids double-releasing it in {@link #destroy()}). <strong>Note:</strong> a user-supplied
     * {@link AsyncHttpClientConfig#getSslContext()} is used as-is for every connection type — if it advertises
     * h2 in ALPN, a {@code wss://} connection may still negotiate h2, which AHC cannot speak for WebSocket. A
     * caller needing WebSocket with a custom context must supply one that negotiates http/1.1.
     */
    private SslContext http1OnlySslContext(AsyncHttpClientConfig config) {
        SslContext ctx = http1OnlySslContext;
        if (ctx == null) {
            synchronized (this) {
                ctx = http1OnlySslContext;
                if (ctx == null) {
                    if (config.getSslContext() != null || !config.isHttp2Enabled()) {
                        ctx = sslContext;
                    } else {
                        try {
                            ctx = buildSslContext(config, false);
                        } catch (SSLException e) {
                            throw new RuntimeException("Failed to build the http/1.1-only SslContext for WebSocket", e);
                        }
                    }
                    http1OnlySslContext = ctx;
                }
            }
        }
        return ctx;
    }

    @Override
    public void init(AsyncHttpClientConfig config) throws SSLException {
        sslContext = buildSslContext(config, true);
        // http1OnlySslContext is built lazily on the first WebSocket connection — see http1OnlySslContext().
    }

    @Override
    public void destroy() {
        ReferenceCountUtil.release(sslContext);
        if (http1OnlySslContext != null && http1OnlySslContext != sslContext) {
            ReferenceCountUtil.release(http1OnlySslContext);
        }
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
