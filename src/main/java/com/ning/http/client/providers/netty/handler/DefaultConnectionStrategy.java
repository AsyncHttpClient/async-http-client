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

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.*;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Values.*;

import org.jboss.netty.handler.codec.http.HttpMessage;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpVersion;

/**
 * Connection strategy implementing standard HTTP 1.0 and 1.1 behaviour.
 */
public class DefaultConnectionStrategy implements ConnectionStrategy {

    /**
     * Implemented in accordance with RFC 7230 section 6.1
     * https://tools.ietf.org/html/rfc7230#section-6.1
     */
    @Override
    public boolean keepAlive(HttpRequest request, HttpResponse response) {
        
        String responseConnectionHeader = connectionHeader(response);
        
        
        if (CLOSE.equalsIgnoreCase(responseConnectionHeader)) {
            return false;
        } else {
            String requestConnectionHeader = connectionHeader(request);
            
            if (request.getProtocolVersion() == HttpVersion.HTTP_1_0) {
                // only use keep-alive if both parties agreed upon it
                return KEEP_ALIVE.equalsIgnoreCase(requestConnectionHeader) && KEEP_ALIVE.equalsIgnoreCase(responseConnectionHeader);
                
            } else {
                // 1.1+, keep-alive is default behavior
                return !CLOSE.equalsIgnoreCase(requestConnectionHeader);
            }
        }
    }

    private String connectionHeader(HttpMessage message) {
        return message.headers().get(CONNECTION);
    }
}
