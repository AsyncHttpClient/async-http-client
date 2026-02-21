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

    private SslContext buildSslContext(AsyncHttpClientConfig config) throws SSLException {
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

        if (config.isEnableHttp2()) {
            sslContextBuilder.applicationProtocolConfig(new ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2,
                    ApplicationProtocolNames.HTTP_1_1));
        }

        return configureSslContextBuilder(sslContextBuilder).build();
    }

    @Override
    public SSLEngine newSslEngine(AsyncHttpClientConfig config, String peerHost, int peerPort) {
        SSLEngine sslEngine = config.isDisableHttpsEndpointIdentificationAlgorithm() ?
                sslContext.newEngine(ByteBufAllocator.DEFAULT) :
                sslContext.newEngine(ByteBufAllocator.DEFAULT, domain(peerHost), peerPort);
        configureSslEngine(sslEngine, config);
        return sslEngine;
    }

    @Override
    public void init(AsyncHttpClientConfig config) throws SSLException {
        sslContext = buildSslContext(config);
    }

    @Override
    public void destroy() {
        ReferenceCountUtil.release(sslContext);
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
