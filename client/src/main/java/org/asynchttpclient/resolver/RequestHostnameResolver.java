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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Request;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.asynchttpclient.netty.SimpleFutureListener;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum RequestHostnameResolver {

    INSTANCE;

    public Future<List<InetSocketAddress>> resolve(Request request, ProxyServer proxy, AsyncHandler<?> asyncHandler) {

        Uri uri = request.getUri();
        final Promise<List<InetSocketAddress>> promise = ImmediateEventExecutor.INSTANCE.newPromise();

        if (request.getAddress() != null) {
            List<InetSocketAddress> resolved = Collections.singletonList(new InetSocketAddress(request.getAddress(), uri.getExplicitPort()));
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

        if (asyncHandlerExtensions != null) {
            try {
                asyncHandlerExtensions.onHostnameResolutionAttempt(name);
            } catch (Exception e) {
                LOGGER.error("onHostnameResolutionAttempt crashed", e);
                promise.tryFailure(e);
                return promise;
            }
        }

        final Future<List<InetAddress>> whenResolved = request.getNameResolver().resolveAll(name);

        whenResolved.addListener(new SimpleFutureListener<List<InetAddress>>() {

            @Override
            protected void onSuccess(List<InetAddress> value) throws Exception {
                ArrayList<InetSocketAddress> socketAddresses = new ArrayList<>(value.size());
                for (InetAddress a : value) {
                    socketAddresses.add(new InetSocketAddress(a, port));
                }
                if (asyncHandlerExtensions != null) {
                    try {
                        asyncHandlerExtensions.onHostnameResolutionSuccess(name, socketAddresses);
                    } catch (Exception e) {
                        LOGGER.error("onHostnameResolutionSuccess crashed", e);
                        promise.tryFailure(e);
                        return;
                    }
                }
                promise.trySuccess(socketAddresses);
            }

            @Override
            protected void onFailure(Throwable t) throws Exception {
                if (asyncHandlerExtensions != null) {
                    try {
                        asyncHandlerExtensions.onHostnameResolutionFailure(name, t);
                    } catch (Exception e) {
                        LOGGER.error("onHostnameResolutionFailure crashed", e);
                        promise.tryFailure(e);
                        return;
                    }
                }
                promise.tryFailure(t);
            }
        });

        return promise;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestHostnameResolver.class);
}
