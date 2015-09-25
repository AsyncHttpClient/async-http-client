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
package org.asynchttpclient.netty.request;

import static org.asynchttpclient.util.HttpUtils.getAuthority;
import static org.asynchttpclient.util.HttpUtils.getNonEmptyPath;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;

public abstract class NettyRequestFactoryBase {

    protected final AsyncHttpClientConfig config;

    public NettyRequestFactoryBase(AsyncHttpClientConfig config) {
        this.config = config;
    }

    protected String requestUri(Uri uri, ProxyServer proxyServer, boolean connect) {
        if (connect)
            return getAuthority(uri);

        else if (proxyServer != null && !uri.useProxyConnect())
            return uri.toUrl();

        else {
            String path = getNonEmptyPath(uri);
            if (isNonEmpty(uri.getQuery()))
                return path + "?" + uri.getQuery();
            else
                return path;
        }
    }

    protected String connectionHeader(boolean allowConnectionPooling, boolean http11) {
        if (allowConnectionPooling)
            return "keep-alive";
        else if (http11)
            return "close";
        else
            return null;
    }
}
