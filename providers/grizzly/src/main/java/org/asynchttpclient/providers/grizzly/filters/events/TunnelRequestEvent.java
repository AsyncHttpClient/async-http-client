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
package org.asynchttpclient.providers.grizzly.filters.events;

import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.uri.UriComponents;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;

/**
 * {@link FilterChainEvent} to initiate CONNECT tunnelling with a proxy server.
 *
 * @since 2.0
 * @author The Grizzly Team.
 */
public final class TunnelRequestEvent implements FilterChainEvent {

    private final FilterChainContext suspendedContext;
    private final ProxyServer proxyServer;
    private final UriComponents uri;

    // ------------------------------------------------------------ Constructors

    public TunnelRequestEvent(final FilterChainContext suspendedContext, final ProxyServer proxyServer, final UriComponents uri) {
        this.suspendedContext = suspendedContext;
        this.proxyServer = proxyServer;
        this.uri = uri;
    }

    // ------------------------------------------- Methods from FilterChainEvent

    @Override
    public Object type() {
        return TunnelRequestEvent.class;
    }

    // ---------------------------------------------------------- Public Methods

    public FilterChainContext getSuspendedContext() {
        return suspendedContext;
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public UriComponents getUri() {
        return uri;
    }
}
