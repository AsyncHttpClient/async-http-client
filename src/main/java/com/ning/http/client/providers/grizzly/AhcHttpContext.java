/*
 * Copyright (c) 2015 Sonatype, Inc. All rights reserved.
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

import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.OutputSink;
import org.glassfish.grizzly.attributes.AttributeStorage;
import org.glassfish.grizzly.http.HttpContext;
import org.glassfish.grizzly.http.HttpRequestPacket;

/**
 * AHC {@link HttpContext}.
 * 
 * @author Grizzly Team
 */
class AhcHttpContext extends HttpContext {
    private final HttpTransactionContext txCtx;
    
    AhcHttpContext(final AttributeStorage attributeStorage,
            final OutputSink outputSink, final Closeable closeable,
            final HttpRequestPacket request,
            final HttpTransactionContext txCtx) {
        super(attributeStorage, outputSink, closeable, request);
        
        this.txCtx = txCtx;
    }
    
    public HttpTransactionContext getHttpTransactionContext() {
        return txCtx;
    }
}
