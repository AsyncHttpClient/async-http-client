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

package org.asynchttpclient.providers.grizzly.filters;

import org.asynchttpclient.providers.grizzly.filters.events.SSLSwitchingEvent;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.ssl.SSLFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public final class SwitchingSSLFilter extends SSLFilter {

    private final boolean secureByDefault;
    final Attribute<Boolean> CONNECTION_IS_SECURE =
        Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(SwitchingSSLFilter.class.getName());

    // ------------------------------------------------------------ Constructors


    public SwitchingSSLFilter(final SSLEngineConfigurator clientConfig,
                              final boolean secureByDefault) {

        super(null, clientConfig);
        this.secureByDefault = secureByDefault;
        addHandshakeListener(new ProtocolHandshakeListener());
    }


    // -------------------------------------------------- Methods from SSLFilter


    @Override
    protected void notifyHandshakeFailed(Connection connection, Throwable t) {
        throw new RuntimeException(t);
    }

    @Override
    public NextAction handleEvent(final FilterChainContext ctx, FilterChainEvent event) throws IOException {

        if (event.type() == SSLSwitchingEvent.class) {
            final SSLSwitchingEvent se = (SSLSwitchingEvent) event;

            if (se.isSecure()) {
                if (se.getAction() != null) {
                    ProtocolHandshakeListener
                            .addListener(ctx.getConnection(),
                                         new HandshakeCompleteListener() {
                                            @Override
                                            public void complete() {
                                                try {
                                                    se.getAction().call();
                                                } catch (Exception e) {
                                                    throw new RuntimeException(e);
                                                }
                                            }
                                        });
                }
                handshake(ctx.getConnection(), null);
            }
            setSecureStatus(se.getConnection(), se.isSecure());
            return ctx.getStopAction();
        }
        return ctx.getInvokeAction();

    }

    @Override
    public NextAction handleRead(FilterChainContext ctx) throws IOException {

        if (isSecure(ctx.getConnection())) {
            return super.handleRead(ctx);
        }
        return ctx.getInvokeAction();

    }

    @Override
    public NextAction handleWrite(FilterChainContext ctx) throws IOException {

        if (isSecure(ctx.getConnection())) {
            return super.handleWrite(ctx);
        }
        return ctx.getInvokeAction();

    }

    @Override
    public void onFilterChainChanged(FilterChain filterChain) {
        // no-op
    }

    // --------------------------------------------------------- Private Methods


    private boolean isSecure(final Connection c) {

        Boolean secStatus = CONNECTION_IS_SECURE.get(c);
        if (secStatus == null) {
            secStatus = secureByDefault;
        }
        return secStatus;

    }

    private void setSecureStatus(final Connection c, final boolean secure) {
        CONNECTION_IS_SECURE.set(c, secure);
    }


    // ---------------------------------------------------------- Nested Classes


    private static interface HandshakeCompleteListener {
            void complete();
    }

    private static final class ProtocolHandshakeListener implements HandshakeListener {


        static ConcurrentHashMap<Connection,HandshakeCompleteListener> listeners =
                new ConcurrentHashMap<Connection,HandshakeCompleteListener>();


        // --------------------------------------- Method from HandshakeListener


        @Override
        public void onStart(Connection connection) {
            // no-op
        }

        @Override
        public void onComplete(Connection connection) {
            final HandshakeCompleteListener listener = listeners.get(connection);
            if (listener != null) {
                removeListener(connection);
                listener.complete();
            }
        }


        // --------------------------------------------- Package Private Methods


        public static void addListener(final Connection c,
                                       final HandshakeCompleteListener listener) {
            listeners.putIfAbsent(c, listener);
        }

        static void removeListener(final Connection c) {
            listeners.remove(c);
        }
    }

}
