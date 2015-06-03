/*
 * Copyright (c) 2012-2015 Sonatype, Inc. All rights reserved.
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

import com.ning.http.client.providers.grizzly.events.SSLSwitchingEvent;
import java.io.IOException;
import javax.net.ssl.SSLEngine;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.ssl.SSLFilter;
import org.glassfish.grizzly.ssl.SSLUtils;

/**
 * The {@link SSLFilter} implementation, which might be activated/deactivated at runtime.
 */
final class SwitchingSSLFilter extends SSLFilter {
    private final boolean secureByDefault;
    final Attribute<Boolean> CONNECTION_IS_SECURE =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(SwitchingSSLFilter.class.getName());
    // -------------------------------------------------------- Constructors

    SwitchingSSLFilter(final SSLEngineConfigurator clientConfig, final boolean secureByDefault) {
        super(null, clientConfig);
        this.secureByDefault = secureByDefault;
    }

    // ---------------------------------------------- Methods from SSLFilter
    @Override
    public NextAction handleEvent(final FilterChainContext ctx,
            final FilterChainEvent event) throws IOException {
        
        if (event.type() == SSLSwitchingEvent.class) {
            final SSLSwitchingEvent se = (SSLSwitchingEvent) event;
            final boolean isSecure = se.isSecure();
            CONNECTION_IS_SECURE.set(se.getConnection(), isSecure);
            
            // if enabling security - create SSLEngine here, because default
            // Grizzly SSLFilter will use host/port info from the Connection, rather
            // than request URL. Specifically this doesn't work with CONNECT tunnels.
            if (isSecure &&
                    SSLUtils.getSSLEngine(ctx.getConnection()) == null) {
                // if SSLEngine is not yet set for the connection - initialize it
                final SSLEngine sslEngine = getClientSSLEngineConfigurator()
                        .createSSLEngine(se.getHost(),
                                se.getPort() == -1 ? 443 : se.getPort()
                        );
                sslEngine.beginHandshake();
                SSLUtils.setSSLEngine(ctx.getConnection(), sslEngine);
            }
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

    @Override
    public void onAdded(FilterChain filterChain) {
        // no-op
    }

    @Override
    public void onRemoved(FilterChain filterChain) {
        // no-op
    }

    
    // ----------------------------------------------------- Private Methods
    private boolean isSecure(final Connection c) {
        Boolean secStatus = CONNECTION_IS_SECURE.get(c);
        if (secStatus == null) {
            secStatus = secureByDefault;
        }
        return secStatus;
    }

    
} // END SwitchingSSLFilter
