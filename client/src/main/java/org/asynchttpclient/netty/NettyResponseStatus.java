/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpResponse;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.uri.Uri;

import java.net.SocketAddress;

/**
 * A class that represent the HTTP response' status line (code + text)
 */
public class NettyResponseStatus extends HttpResponseStatus {

    private final HttpResponse response;
    private final SocketAddress remoteAddress;
    private final SocketAddress localAddress;

    public NettyResponseStatus(Uri uri, HttpResponse response, Channel channel) {
        super(uri);
        this.response = response;
        if (channel != null) {
            remoteAddress = channel.remoteAddress();
            localAddress = channel.localAddress();
        } else {
            remoteAddress = null;
            localAddress = null;
        }
    }

    /**
     * Return the response status code
     *
     * @return the response status code
     */
    @Override
    public int getStatusCode() {
        return response.status().code();
    }

    /**
     * Return the response status text
     *
     * @return the response status text
     */
    @Override
    public String getStatusText() {
        return response.status().reasonPhrase();
    }

    @Override
    public String getProtocolName() {
        return response.protocolVersion().protocolName();
    }

    @Override
    public int getProtocolMajorVersion() {
        return response.protocolVersion().majorVersion();
    }

    @Override
    public int getProtocolMinorVersion() {
        return response.protocolVersion().minorVersion();
    }

    @Override
    public String getProtocolText() {
        return response.protocolVersion().text();
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return localAddress;
    }
}
