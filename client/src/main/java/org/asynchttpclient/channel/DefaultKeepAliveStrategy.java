/*
 *    Copyright (c) 2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.channel;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import org.asynchttpclient.Request;

import java.net.InetSocketAddress;

import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;

/**
 * Connection strategy implementing standard HTTP 1.0/1.1 behavior.
 */
public class DefaultKeepAliveStrategy implements KeepAliveStrategy {

    /**
     * Implemented in accordance with RFC 7230 section 6.1 https://tools.ietf.org/html/rfc7230#section-6.1
     */
    @Override
    public boolean keepAlive(InetSocketAddress remoteAddress, Request ahcRequest, HttpRequest request, HttpResponse response) {
        return HttpUtil.isKeepAlive(response) &&
                HttpUtil.isKeepAlive(request) &&
                // support non-standard Proxy-Connection
                !response.headers().contains("Proxy-Connection", CLOSE, true);
    }
}
