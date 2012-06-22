/*
 * Copyright (c) 2012 Sonatype, Inc. All rights reserved.
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

import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;

/**
 * This class may be provided as an option to the {@link GrizzlyAsyncHttpProviderConfig}
 * and allows low-level customization of the {@link TCPNIOTransport} beyond the
 * defaults typically used.
 * 
 * @author The Grizzly Team
 * @since 1.7.0
 */
public interface TransportCustomizer {

    /**
     * Customizes the configuration of the provided {@link TCPNIOTransport} 
     * and {@link FilterChainBuilder} instances.
     * 
     * @param transport the {@link TCPNIOTransport} instance for this client.
     * @param filterChainBuilder the {@link FilterChainBuilder} that will
     *   produce the {@link org.glassfish.grizzly.filterchain.FilterChain} that
     *   will be used to send/receive data.  The FilterChain will be populated
     *   with the Filters typically used for processing HTTP client requests.
     *   These filters should generally be left alone.  But this does allow
     *   adding additional filters to the chain to add additional features.
     */
    void customize(final TCPNIOTransport transport,
                   final FilterChainBuilder filterChainBuilder);
    
}
