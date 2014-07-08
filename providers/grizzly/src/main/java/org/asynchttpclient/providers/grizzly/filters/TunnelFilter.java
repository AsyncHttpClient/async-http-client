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

import java.io.IOException;

import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.providers.grizzly.Utils;
import org.asynchttpclient.providers.grizzly.filters.events.TunnelRequestEvent;
import org.asynchttpclient.uri.UriComponents;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;

/**
 * This <code>Filter</code> is responsible for HTTP CONNECT
 * tunnelling when a connection should be secure and required to
 * go through a proxy.
 *
 * @since 2.0
 * @author The Grizzly Team
 */
public final class TunnelFilter extends BaseFilter {

    private final ProxyServer proxyServer;
    private final UriComponents uri;

    // ------------------------------------------------------------ Constructors

    public TunnelFilter(final ProxyServer proxyServer, final UriComponents uri) {
        this.proxyServer = proxyServer;
        this.uri = uri;
    }

    // ----------------------------------------------------- Methods from Filter

    @Override
    public NextAction handleConnect(FilterChainContext ctx) throws IOException {
        // We suspend the FilterChainContext here to prevent
        // notification of other filters of the connection event.
        // This allows us to control when the connection is returned
        // to the user - we ensure that the tunnel is properly established
        // before the user request is sent.
        ctx.suspend();

        // This connection is special and shouldn't be tracked.
        Utils.connectionIgnored(ctx.getConnection(), true);

        // This event will be handled by the AsyncHttpClientFilter.
        // It will send the CONNECT request and process the response.
        // When tunnel is complete, the AsyncHttpClientFilter will
        // send this event back to this filter in order to notify
        // it that the request processing is complete.
        final TunnelRequestEvent tunnelRequestEvent = new TunnelRequestEvent(ctx, proxyServer, uri);
        ctx.notifyUpstream(tunnelRequestEvent);

        // This typically isn't advised, however, we need to be able to
        // read the response from the proxy and OP_READ isn't typically
        // enabled on the connection until all of the handleConnect()
        // processing is complete.
        ctx.getConnection().enableIOEvent(IOEvent.READ);

        // Tell the FilterChain that we're suspending further handleConnect
        // processing.
        return ctx.getSuspendAction();
    }

    @Override
    public NextAction handleEvent(FilterChainContext ctx, FilterChainEvent event) throws IOException {
        if (event.type() == TunnelRequestEvent.class) {
            TunnelRequestEvent tunnelRequestEvent = (TunnelRequestEvent) event;

            // Clear ignore status.  Any further use of the connection will
            // be bound by normal AHC connection processing.
            Utils.connectionIgnored(ctx.getConnection(), false);

            // Obtain the context that was previously suspended and resume.
            // We pass in Invoke Action so the filter chain will call
            // handleConnect on the next filter.
            FilterChainContext suspendedContext = tunnelRequestEvent.getSuspendedContext();
            suspendedContext.resume(ctx.getInvokeAction());

            // Stop further event processing.
            ctx.getStopAction();
        }
        return ctx.getInvokeAction();
    }
}
