/*
 * Copyright (c) 2013 Sonatype, Inc. All rights reserved.
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

package org.asynchttpclient.providers.grizzly;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.providers.grizzly.filters.ProxyFilter;
import org.asynchttpclient.providers.grizzly.filters.TunnelFilter;
import org.asynchttpclient.uri.Uri;
import org.glassfish.grizzly.Processor;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;

final class ProxyAwareConnectorHandler extends TCPNIOConnectorHandler {

    private FilterChainBuilder nonSecureTemplate;
    private FilterChainBuilder secureTemplate;
    private AsyncHttpClientConfig clientConfig;
    private Uri uri;
    private ProxyServer proxyServer;

    // ------------------------------------------------------------ Constructors

    private ProxyAwareConnectorHandler(final TCPNIOTransport transport) {
        super(transport);
    }

    // ---------------------------------------------------------- Public Methods

    public static Builder builder(final TCPNIOTransport transport) {
        return new ProxyAwareConnectorHandler.Builder(transport);
    }

    // ------------------------------------------- Methods from ConnectorHandler

    @Override
    public Processor getProcessor() {
        return ((proxyServer != null) ? createProxyFilterChain() : createFilterChain());
    }

    // --------------------------------------------------------- Private Methods

    private FilterChain createFilterChain() {
        return Utils.isSecure(uri) ? secureTemplate.build() : nonSecureTemplate.build();
    }

    private FilterChain createProxyFilterChain() {
        final FilterChainBuilder builder = FilterChainBuilder.stateless();
        if (Utils.isSecure(uri)) {
            builder.addAll(secureTemplate);
            updateSecureFilterChain(builder);
        } else {
            builder.addAll(nonSecureTemplate);
            updateNonSecureFilterChain(builder);
        }
        return builder.build();
    }

    private void updateSecureFilterChain(final FilterChainBuilder builder) {
        builder.add(1, new TunnelFilter(proxyServer, uri));
        final int idx = builder.indexOfType(HttpClientFilter.class);
        assert (idx != -1);
        builder.add(idx + 1, new ProxyFilter(proxyServer, clientConfig, true));
    }

    private void updateNonSecureFilterChain(final FilterChainBuilder builder) {
        final int idx = builder.indexOfType(HttpClientFilter.class);
        assert (idx != -1);
        builder.add(idx + 1, new ProxyFilter(proxyServer, clientConfig, false));
    }

    // ---------------------------------------------------------- Nested Classes

    public static final class Builder extends TCPNIOConnectorHandler.Builder {

        final ProxyAwareConnectorHandler connectorHandler;

        // -------------------------------------------------------- Constructors

        private Builder(final TCPNIOTransport transport) {
            connectorHandler = new ProxyAwareConnectorHandler(transport);
        }

        // ----------------------------------------------------- Builder Methods

        public Builder secureFilterChainTemplate(final FilterChainBuilder secureTemplate) {
            connectorHandler.secureTemplate = secureTemplate;
            return this;
        }

        public Builder nonSecureFilterChainTemplate(final FilterChainBuilder nonSecureTemplate) {
            connectorHandler.nonSecureTemplate = nonSecureTemplate;
            return this;
        }

        public Builder asyncHttpClientConfig(final AsyncHttpClientConfig clientConfig) {
            connectorHandler.clientConfig = clientConfig;
            return this;
        }

        public Builder uri(final Uri uri) {
            connectorHandler.uri = uri;
            return this;
        }

        public Builder proxyServer(final ProxyServer proxyServer) {
            connectorHandler.proxyServer = proxyServer;
            return this;
        }

        @Override
        public ProxyAwareConnectorHandler build() {
            assert (connectorHandler.secureTemplate != null);
            assert (connectorHandler.nonSecureTemplate != null);
            assert (connectorHandler.clientConfig != null);
            assert (connectorHandler.uri != null);
            return connectorHandler;
        }
    } // END Builder
}
