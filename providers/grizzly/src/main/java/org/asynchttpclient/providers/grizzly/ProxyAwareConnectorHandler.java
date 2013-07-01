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
import org.asynchttpclient.Request;
import org.asynchttpclient.providers.grizzly.filters.ProxyFilter;
import org.asynchttpclient.providers.grizzly.filters.TunnelFilter;
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
    private Request request;
    private ProxyServer proxyServer;

    // ------------------------------------------------------------ Constructors


    private ProxyAwareConnectorHandler(final TCPNIOTransport transport) {
        super(transport);
    }


    // ---------------------------------------------------------- Public Methods


    public void setRequest(final Request request) {
        assert(request != null);
        this.request = request;
    }

    public void setProxy(final ProxyServer proxyServer) {
        this.proxyServer = proxyServer;
    }

    public static Builder builder(final TCPNIOTransport transport) {
        return new ProxyAwareConnectorHandler.Builder(transport);
    }


    // ------------------------------------------- Methods from ConnectorHandler


    @Override
    public Processor getProcessor() {
        return ((proxyServer != null)
                    ? createProxyFilterChain(request, proxyServer)
                    : createFilterChain(request));
    }


    // --------------------------------------------------------- Private Methods


    private FilterChain createFilterChain(final Request request) {
        return isRequestSecure(request)
                   ? secureTemplate.build()
                   : nonSecureTemplate.build();
    }

    private FilterChain createProxyFilterChain(final Request request,
                                               final ProxyServer proxyServer) {
        final FilterChainBuilder builder = FilterChainBuilder.stateless();
        if (isRequestSecure(request)) {
            builder.addAll(secureTemplate);
            updateSecureFilterChain(builder, request, proxyServer);
        } else {
            builder.addAll(nonSecureTemplate);
            updateNonSecureFilterChain(builder, proxyServer);
        }
        return builder.build();
    }

    private static boolean isRequestSecure(final Request request) {
        final String p = request.getURI().getScheme();
        return p.equals("https") || p.equals("wss");
    }

    private void updateSecureFilterChain(final FilterChainBuilder builder,
                                         final Request request,
                                         final ProxyServer proxyServer) {
        builder.add(1, new TunnelFilter(proxyServer,
                                        request.getURI())); // Insert after the the transport filter
        final int idx = builder.indexOfType(HttpClientFilter.class);
        assert (idx != -1);
        builder.add(idx + 1, new ProxyFilter(proxyServer, clientConfig, true));
    }

    private void updateNonSecureFilterChain(final FilterChainBuilder builder,
                                            final ProxyServer proxyServer) {
        final int idx = builder.indexOfType(HttpClientFilter.class);
        assert (idx != -1);
        builder.add(idx + 1, new ProxyFilter(proxyServer, clientConfig, false));
    }


    // ---------------------------------------------------------- Nested Classes


    public static final class Builder extends TCPNIOConnectorHandler.Builder {

        final ProxyAwareConnectorHandler connectorHandler;


        // -------------------------------------------------------- Constructors


        private Builder(final TCPNIOTransport transport) {
            super(transport);
            connectorHandler = new ProxyAwareConnectorHandler(transport);
        }


        // ----------------------------------------------------- Builder Methods


        public Builder setSecureFilterChainTemplate(final FilterChainBuilder secureTemplate) {
            connectorHandler.secureTemplate = secureTemplate;
            return this;
        }

        public Builder setNonSecureFilterChainTemplate(final FilterChainBuilder nonSecureTemplate) {
            connectorHandler.nonSecureTemplate = nonSecureTemplate;
            return this;
        }

        public Builder setAsyncHttpClientConfig(final AsyncHttpClientConfig clientConfig) {
            connectorHandler.clientConfig = clientConfig;
            return this;
        }

        @Override
        public ProxyAwareConnectorHandler build() {
            assert(connectorHandler.secureTemplate != null);
            assert(connectorHandler.nonSecureTemplate != null);
            assert(connectorHandler.clientConfig != null);
            return connectorHandler;
        }

    } // END Builder
}
