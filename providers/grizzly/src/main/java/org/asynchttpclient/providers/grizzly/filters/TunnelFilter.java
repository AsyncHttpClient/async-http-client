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

import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.providers.grizzly.Utils;
import org.asynchttpclient.providers.grizzly.filters.events.TunnelRequestEvent;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;

import java.io.IOException;
import java.net.URI;

/**
 * This <code>Filter</code> is responsible for HTTP CONNECT
 * tunnelling when a connection should be secure and done via
 * proxy.
 *
 * @since 2.0
 * @author The Grizzly Team
 */
public final class TunnelFilter extends BaseFilter {

    private static final Attribute<Boolean> TUNNEL_IN_PROGRESS =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(TunnelFilter.class.getName());

    private final ProxyServer proxyServer;
    private final URI uri;

    // ------------------------------------------------------------ Constructors


    public TunnelFilter(final ProxyServer proxyServer, final URI uri) {
        this.proxyServer = proxyServer;
        this.uri = uri;
    }


    // ----------------------------------------------------- Methods from Filter

    @Override
    public NextAction handleConnect(FilterChainContext ctx)
    throws IOException {
        if (TUNNEL_IN_PROGRESS.get(ctx.getConnection()) == null) {
            TUNNEL_IN_PROGRESS.set(ctx.getConnection(), Boolean.TRUE);
            ctx.suspend();
            Utils.connectionIgnored(ctx.getConnection(), true);
            final TunnelRequestEvent tunnelRequestEvent =
                    new TunnelRequestEvent(ctx, proxyServer, uri);
            ctx.notifyUpstream(tunnelRequestEvent);
            return ctx.getSuspendAction();
        } else {
            TUNNEL_IN_PROGRESS.remove(ctx.getConnection());
            return ctx.getInvokeAction();
        }
    }

    @Override
    public NextAction handleEvent(FilterChainContext ctx, FilterChainEvent event)
    throws IOException {
        if (event.type() == TunnelRequestEvent.class) {
            TunnelRequestEvent tunnelRequestEvent = (TunnelRequestEvent) event;
            if (tunnelInProgress(ctx.getConnection())) {
                Utils.connectionIgnored(ctx.getConnection(), false);
                FilterChainContext suspendedContext = tunnelRequestEvent.getSuspendedContext();
                suspendedContext.resume();
            }
            ctx.getStopAction();
        }
        return ctx.getInvokeAction();
    }


    // ---------------------------------------------------------- Public Methods


    public static boolean tunnelInProgress(final Connection connection) {
        return (TUNNEL_IN_PROGRESS.get(connection) != null);
    }

} // END TunnelFilter
