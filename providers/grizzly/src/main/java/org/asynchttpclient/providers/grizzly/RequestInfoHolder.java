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

package org.asynchttpclient.providers.grizzly;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Request;

public class RequestInfoHolder {

    private final GrizzlyAsyncHttpProvider provider;
    private final Request request;
    private final AsyncHandler handler;
    private final GrizzlyResponseFuture future;
    private final HttpTxContext httpTxContext;


    // ------------------------------------------------------------ Constructors


    public RequestInfoHolder(final GrizzlyAsyncHttpProvider provider,
                             final Request request,
                             final AsyncHandler handler,
                             final GrizzlyResponseFuture future,
                             final HttpTxContext httpTxContext) {
        this.provider = provider;
        this.request = request;
        this.handler = handler;
        this.future = future;
        this.httpTxContext = httpTxContext;
    }


    // ---------------------------------------------------------- Public Methods


    public GrizzlyAsyncHttpProvider getProvider() {
        return provider;
    }

    public Request getRequest() {
        return request;
    }

    public AsyncHandler getHandler() {
        return handler;
    }

    public GrizzlyResponseFuture getFuture() {
        return future;
    }

    public HttpTxContext getHttpTxContext() {
        return httpTxContext;
    }
}
