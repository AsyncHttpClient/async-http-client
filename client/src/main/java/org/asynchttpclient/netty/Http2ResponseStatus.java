/*
 *    Copyright (c) 2024 AsyncHttpClient Project. All rights reserved.
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
import io.netty.handler.codec.http.HttpResponseStatus;
import org.asynchttpclient.uri.Uri;

import java.net.SocketAddress;

public class Http2ResponseStatus extends org.asynchttpclient.HttpResponseStatus {

    private final HttpResponseStatus status;
    private final SocketAddress remoteAddress;
    private final SocketAddress localAddress;

    public Http2ResponseStatus(Uri uri, int statusCode, Channel channel) {
        super(uri);
        this.status = HttpResponseStatus.valueOf(statusCode);
        if (channel != null) {
            // For HTTP/2 stream channels, get addresses from parent
            Channel parentChannel = channel.parent() != null ? channel.parent() : channel;
            remoteAddress = parentChannel.remoteAddress();
            localAddress = parentChannel.localAddress();
        } else {
            remoteAddress = null;
            localAddress = null;
        }
    }

    @Override
    public int getStatusCode() {
        return status.code();
    }

    @Override
    public String getStatusText() {
        return status.reasonPhrase();
    }

    @Override
    public String getProtocolName() {
        return "HTTP";
    }

    @Override
    public int getProtocolMajorVersion() {
        return 2;
    }

    @Override
    public int getProtocolMinorVersion() {
        return 0;
    }

    @Override
    public String getProtocolText() {
        return "HTTP/2.0";
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
