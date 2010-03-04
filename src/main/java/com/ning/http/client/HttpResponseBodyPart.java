/*
 * Copyright 2010 Ning, Inc.
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
package com.ning.http.client;

import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * A callback class used when an HTTP response body is received.
 */
public class HttpResponseBodyPart extends HttpContent {
    private final HttpChunk chunk;
    private final HttpResponse httpResponse;

    public HttpResponseBodyPart(Response response, HttpResponse httpResponse) {
        super(response);
        this.chunk = null;
        this.httpResponse = httpResponse;
    }

    public HttpResponseBodyPart(Response response, HttpChunk chunk) {
        super(response);
        this.chunk = chunk;
        this.httpResponse = null;        
    }

    /**
     * Return the response body's part bytes received.
     * @return the response body's part bytes received.
     */
    public byte[] getBodyPartBytes(){
        if (chunk != null){
            return chunk.getContent().array();
        } else {
            return httpResponse.getContent().array();
        }
    }
}
