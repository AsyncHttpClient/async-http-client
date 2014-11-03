/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package com.ning.http.client.providers.netty.handler;

import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMessage;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * Connection strategy implementing standard HTTP 1.1 behaviour.
 */
public class Http1Point1ConnectionStrategy implements ConnectionStrategy {

    /**
     * Implemented in accordance with RFC 7230 section 6.1
     * https://tools.ietf.org/html/rfc7230#section-6.1
     */
    @Override
    public boolean keepAlive(HttpRequest httpRequest, HttpResponse response) {
        return isConnectionKeepAlive(httpRequest) && isConnectionKeepAlive(response);
    }

    public boolean isConnectionKeepAlive(HttpMessage message) {
        return !HttpHeaders.Values.CLOSE.equalsIgnoreCase(message.headers().get(HttpHeaders.Names.CONNECTION));
    }
}
