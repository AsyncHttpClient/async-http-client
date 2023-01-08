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
package org.asynchttpclient.channel;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.asynchttpclient.Request;

import java.net.InetSocketAddress;

@FunctionalInterface
public interface KeepAliveStrategy {

    /**
     * Determines whether the connection should be kept alive after this HTTP message exchange.
     *
     * @param remoteAddress the remote InetSocketAddress associated with the request
     * @param ahcRequest    the Request, as built by AHC
     * @param nettyRequest  the HTTP request sent to Netty
     * @param nettyResponse the HTTP response received from Netty
     * @return true if the connection should be kept alive, false if it should be closed.
     */
    boolean keepAlive(InetSocketAddress remoteAddress, Request ahcRequest, HttpRequest nettyRequest, HttpResponse nettyResponse);
}
