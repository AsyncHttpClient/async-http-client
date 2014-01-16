/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.providers.netty.request;

import org.asynchttpclient.providers.netty.request.body.NettyBody;

import io.netty.handler.codec.http.HttpRequest;

public class NettyRequest {

    private final HttpRequest httpRequest;
    private final NettyBody body;

    public NettyRequest(HttpRequest httpRequest, NettyBody body) {
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
