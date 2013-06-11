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
import org.asynchttpclient.util.ProxyUtils;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.EmptyIOEventProcessingHandler;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;

final class ProxyAwareConnectorHandler extends TCPNIOConnectorHandler {

    private FilterChainBuilder nonSecureTemplate;
    private FilterChainBuilder secureTemplate;
    private AsyncHttpClientConfig clientConfig;

    // ------------------------------------------------------------ Constructors


    private ProxyAwareConnectorHandler(final TCPNIOTransport transport) {
        super(transport);
    }


    // ---------------------------------------------------------- Public Methods


    public void connect(final Request request,
                        final SocketAddress localAddress,
                        final CompletionHandler<Connection> completionHandler) {
        final ProxyServer proxyServer =
                ProxyUtils.getProxyServer(clientConfig, request);
        final SocketAddress remoteAddress =
                getRemoteAddress(request, proxyServer);
        super.connect(remoteAddress,
                      localAddress,
                      ((proxyServer != null)
                              ? new ProxyAdaptingCompletionHandler(request, proxyServer, completionHandler)
                              : new FilterChainInstallationHandler(request, completionHandler)));
    }

    public static Builder builder(final TCPNIOTransport transport) {
        return new ProxyAwareConnectorHandler.Builder(transport);
    }


    // --------------------------------------------------------- Private Methods


    private SocketAddress getRemoteAddress(final Request request,
                                           final ProxyServer proxyServer) {
        final URI requestUri = request.getURI();
        final String host = ((proxyServer != null)
                                     ? proxyServer.getHost()
                                     : requestUri.getHost());
        final int port = ((proxyServer != null)
                                  ? proxyServer.getPort()
                                  : requestUri.getPort());
        return new InetSocketAddress(host, getPort(request.getURI(), port));
    }

    private static int getPort(final URI uri, final int p) {
        int port = p;
        if (port == -1) {
            final String protocol = uri.getScheme().toLowerCase();
            if ("http".equals(protocol) || "ws".equals(protocol)) {
                port = 80;
            } else if ("https".equals(protocol) || "wss".equals(protocol)) {
                port = 443;
            } else {
                throw new IllegalArgumentException(
                        "Unknown protocol: " + protocol);
            }
        }
        return port;
    }


    // ---------------------------------------------------------- Nested Classes


    private class FilterChainInstallationHandler implements CompletionHandler<Connection> {

        final Request request;
        final CompletionHandler<Connection> delegate;

        // -------------------------------------------------------- Constructors


        FilterChainInstallationHandler(final Request request,
                                       final CompletionHandler<Connection> delegate) {
            this.request = request;
            this.delegate = delegate;
        }


        // -------------------------------------- Methods from CompletionHandler

        @Override
        public void cancelled() {
            delegate.cancelled();
        }

        @Override
        public void failed(Throwable throwable) {
            delegate.failed(throwable);
        }

        @Override
        public void completed(Connection result) {
            FilterChainBuilder b = isRequestSecure()
                                       ? secureTemplate
                                       : nonSecureTemplate;
            result.setProcessor(b.build());
            fireConnectEvent(result);
        }

        @Override
        public void updated(Connection result) {
            delegate.updated(result);
        }


        // --------------------------------------------------- Protected Methods


        boolean isRequestSecure() {
            final String p = request.getURI().getScheme();
            return p.equals("https") || p.equals("wss");
        }

        /*
         * We have to fire this event ourselves as at the point in time it
         * would normally fire the event, we haven't been called to set the
         * FilterChain on the connection.  Not doing so will break idle
         * timeout detection.
         */
        void fireConnectEvent(Connection result) {
            result.getTransport().fireIOEvent(IOEvent.CONNECTED,
                                              result,
                                              new EnableReadHandler(delegate));
        }

    } // END FilterChainInstallationHandler


    private final class ProxyAdaptingCompletionHandler extends FilterChainInstallationHandler {

        final ProxyServer proxyServer;


        // --------------------------------------------------------- Constructor


        private ProxyAdaptingCompletionHandler(final Request request,
                                               final ProxyServer proxyServer,
                                               final CompletionHandler<Connection> delegate) {
            super(request, delegate);
            this.proxyServer = proxyServer;
        }


        // -------------------------------------- Methods from CompletionHandler


        @Override
        public void cancelled() {
            delegate.cancelled();
        }

        @Override
        public void failed(Throwable throwable) {
            delegate.failed(throwable);
        }

        @Override
        public void completed(Connection result) {
            // Connection is completed.  Before we continue, we need
            // to adapt the FilterChain based on the proxy type.
            // If we're dealing with a secure HTTP proxy, we need to use
            // a custom filter that will establish a tunnel using CONNECT.
            // If we're dealing with a non-secure HTTP proxy, then we use
            // a different filter that will update the request accordingly
            // before passing it to the codec filter.
            // Once the FilterChain has been adapted, set it on the Connection,
            // and invoke the completed() method on the delegate to continue
            // processing.
            final FilterChainBuilder builder = FilterChainBuilder.stateless();
            if (isRequestSecure()) {
                builder.addAll(secureTemplate);
                updateSecureFilterChain(builder, proxyServer);
            } else {
                builder.addAll(nonSecureTemplate);
                updateNonSecureFilterChain(builder, proxyServer);
            }
            result.setProcessor(builder.build());

            fireConnectEvent(result);
        }

        @Override
        public void updated(Connection result) {
            delegate.updated(result);
        }


        // ----------------------------------------------------- Private Methods


        private void updateSecureFilterChain(final FilterChainBuilder builder,
                                             final ProxyServer proxyServer) {
            builder.add(1, new TunnelFilter(proxyServer, request.getURI())); // Insert after the the transport filter
            final int idx = builder.indexOfType(HttpClientFilter.class);
            assert (idx != -1);
            builder.add(idx + 1, new ProxyFilter(proxyServer, clientConfig, true));
        }

        private void updateNonSecureFilterChain(final FilterChainBuilder builder,
                                                final ProxyServer proxyServer) {
            final int idx = builder.indexOfType(HttpClientFilter.class);
            assert(idx != -1);
            builder.add(idx + 1, new ProxyFilter(proxyServer, clientConfig, false));
        }

    } // ProxyAdaptingCompletionHandler


    private static final class EnableReadHandler extends
            EmptyIOEventProcessingHandler {

        private final CompletionHandler<Connection> completionHandler;

        private EnableReadHandler(final CompletionHandler<Connection> completionHandler) {
            this.completionHandler = completionHandler;
        }

        @Override
        public void onReregister(final Context context) throws IOException {
            onComplete(context, null);
        }

        @Override
        public void onNotRun(final Context context) throws IOException {
            onComplete(context, null);
        }

        @Override
        public void onComplete(final Context context, final Object data)
        throws IOException {
            final NIOConnection connection =
                    (NIOConnection) context.getConnection();

            if (completionHandler != null) {
                completionHandler.completed(connection);
            }

            if (!connection.isStandalone()) {
                connection.enableIOEvent(IOEvent.READ);
            }
        }

        @Override
        public void onError(final Context context, final Object description)
        throws IOException {
            context.getConnection().closeSilently();
        }

    } // END EnableReadHandler


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
