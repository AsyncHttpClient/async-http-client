/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.resolver;

import static org.asynchttpclient.handler.AsyncHandlerExtensionsUtils.toAsyncHandlerExtensions;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Request;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.asynchttpclient.netty.SimpleFutureListener;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;

public enum RequestHostnameResolver {

    INSTANCE;

    public Future<List<InetSocketAddress>> resolve(Request request, ProxyServer proxy, AsyncHandler<?> asyncHandler) {

        Uri uri = request.getUri();

        if (request.getAddress() != null) {
            List<InetSocketAddress> resolved = Collections.singletonList(new InetSocketAddress(request.getAddress(), uri.getExplicitPort()));
            Promise<List<InetSocketAddress>> promise = ImmediateEventExecutor.INSTANCE.newPromise();
            return promise.setSuccess(resolved);

        }

        // don't notify on explicit address
        final AsyncHandlerExtensions asyncHandlerExtensions = request.getAddress() == null ? toAsyncHandlerExtensions(asyncHandler) : null;
        final String name;
        final int port;

        if (proxy != null && !proxy.isIgnoredForHost(uri.getHost())) {
            name = proxy.getHost();
            port = uri.isSecured() ? proxy.getSecuredPort() : proxy.getPort();
        } else {
            name = uri.getHost();
            port = uri.getExplicitPort();
        }

        if (asyncHandlerExtensions != null)
            asyncHandlerExtensions.onHostnameResolutionAttempt(name);

        final Future<List<InetSocketAddress>> whenResolved = request.getNameResolver().resolve(name, port);

        if (asyncHandlerExtensions == null)
            return whenResolved;

        else {
            Promise<List<InetSocketAddress>> promise = ImmediateEventExecutor.INSTANCE.newPromise();
            
            whenResolved.addListener(new SimpleFutureListener<List<InetSocketAddress>>() {
                
                @Override
                protected void onSuccess(List<InetSocketAddress> addresses) throws Exception {
                    asyncHandlerExtensions.onHostnameResolutionSuccess(name, addresses);
                    promise.setSuccess(addresses);
                }
                
                @Override
                protected void onFailure(Throwable t) throws Exception {
                    asyncHandlerExtensions.onHostnameResolutionFailure(name, t);
                    promise.setFailure(t);
                }
            });
            
            return promise;
        }
    }
}
