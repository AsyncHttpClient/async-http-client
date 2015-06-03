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
package com.ning.http.client.providers.grizzly.events;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.FilterChainEvent;

// ------------------------------------------------------ Nested Classes

public final class SSLSwitchingEvent implements FilterChainEvent {
    private final boolean secure;
    private final Connection connection;
    private final String host;
    private final int port;
    
    // ---------------------------------------------------- Constructors

    public SSLSwitchingEvent(final Connection c, final boolean secure) {
        this(c, secure, null, -1);
    }
    
    public SSLSwitchingEvent(final Connection c, final boolean secure,
            final String host, final int port) {
        this.secure = secure;
        connection = c;
        this.host = host;
        this.port = port;
    }
    // ----------------------------------- Methods from FilterChainEvent

    @Override
    public Object type() {
        return SSLSwitchingEvent.class;
    }

    public boolean isSecure() {
        return secure;
    }

    public Connection getConnection() {
        return connection;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }        
} // END SSLSwitchingEvent
