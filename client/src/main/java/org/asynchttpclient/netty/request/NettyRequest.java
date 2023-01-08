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
package org.asynchttpclient.netty.request;

import io.netty.handler.codec.http.HttpRequest;
import org.asynchttpclient.netty.request.body.NettyBody;

public final class NettyRequest {

    private final HttpRequest httpRequest;
    private final NettyBody body;

    NettyRequest(HttpRequest httpRequest, NettyBody body) {
        this.httpRequest = httpRequest;
        this.body = body;
    }

    public HttpRequest getHttpRequest() {
        return httpRequest;
    }

    public NettyBody getBody() {
        return body;
    }
}
