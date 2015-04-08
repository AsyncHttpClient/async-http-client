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

import com.ning.http.client.providers.grizzly.HttpTransactionContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;

/**
 * {@link FilterChainEvent} to gracefully complete the request-response processing
 * when {@link Connection} is getting closed by the remote host.
 *
 * @since 1.8.7
 * @author The Grizzly Team
 */
public class GracefulCloseEvent implements FilterChainEvent {
    private final HttpTransactionContext httpTxContext;

    public GracefulCloseEvent(HttpTransactionContext httpTxContext) {
        this.httpTxContext = httpTxContext;
    }

    public HttpTransactionContext getHttpTxContext() {
        return httpTxContext;
    }

    @Override
    public Object type() {
        return GracefulCloseEvent.class;
    }
    
} // END GracefulCloseEvent
