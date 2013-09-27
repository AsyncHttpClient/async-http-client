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

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.FilterChainEvent;


/**
 * {@link FilterChainEvent} to dynamically enable/disable the SSLFilter on
 * a per-connection basis.
 *
 * @since 2.0
 * @author The Grizzly Team
 */
public final class SSLSwitchingEvent implements FilterChainEvent {

    private final boolean secure;
    private final Connection connection;

    // ------------------------------------------------------------ Constructors


    public SSLSwitchingEvent(final boolean secure, final Connection c) {

        this.secure = secure;
        connection = c;

    }

    // ------------------------------------------- Methods from FilterChainEvent


    @Override
    public Object type() {
        return SSLSwitchingEvent.class;
    }


    // ---------------------------------------------------------- Public Methods


    public boolean isSecure() {
        return secure;
    }

    public Connection getConnection() {
        return connection;
    }

}
