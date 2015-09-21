/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.channel;

import static org.asynchttpclient.util.AsyncHttpProviderUtils.getExplicitPort;
import static org.asynchttpclient.util.ProxyUtils.ignoreProxy;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Request;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.HttpUtils;

public abstract class ChannelConnector {

    protected final AsyncHandlerExtensions asyncHandlerExtensions;
    protected final InetSocketAddress localAddress;
    protected final InetSocketAddress[] remoteAddresses;
    protected volatile int i = 0;
    
    public ChannelConnector(Request request, ProxyServer proxy, boolean useProxy, AsyncHandler<?> asyncHandler) throws UnknownHostException {

        this.asyncHandlerExtensions = asyncHandler instanceof AsyncHandlerExtensions ? (AsyncHandlerExtensions) asyncHandler : null;
        NameResolution[] resolutions;
        Uri uri = request.getUri();
        int port = getExplicitPort(uri);

        if (request.getInetAddress() != null) {
            resolutions = new NameResolution[] { new NameResolution(request.getInetAddress()) };

        } else if (!useProxy || ignoreProxy(proxy, uri.getHost())) {
            resolutions = request.getNameResolver().resolve(uri.getHost());

        } else {
            resolutions = request.getNameResolver().resolve(proxy.getHost());
            port = HttpUtils.isSecure(uri) ? proxy.getSecuredPort(): proxy.getPort();
        }

        if (asyncHandlerExtensions != null)
            asyncHandlerExtensions.onDnsResolved(resolutions);
        
        remoteAddresses = new InetSocketAddress[resolutions.length];
        for (int i = 0; i < resolutions.length; i ++) {
            remoteAddresses[i] = new InetSocketAddress(resolutions[i].address, port);
        }
        
        if (request.getLocalAddress() != null) {
            localAddress = new InetSocketAddress(request.getLocalAddress(), 0);
                    
        } else {
            localAddress = null;
        }
    }

    protected boolean pickNextRemoteAddress() {
        i++;
        return i < remoteAddresses.length;
    }
}
