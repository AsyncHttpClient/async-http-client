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
 *
 */
package org.asynchttpclient.providers.netty.response;

import io.netty.handler.codec.http.HttpResponse;

import java.net.URI;
import java.util.List;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;

/**
 * A class that represent the HTTP response' status line (code + text)
 */
public class ResponseStatus extends HttpResponseStatus {
    
    private final HttpResponse response;

    public ResponseStatus(URI uri, HttpResponse response, AsyncHttpClientConfig config) {
        super(uri, config);
        this.response = response;
    }
    
    @Override
    public Response prepareResponse(HttpResponseHeaders headers, List<HttpResponseBodyPart> bodyParts) {
        return new NettyResponse(this, headers, bodyParts);
    }

    /**
     * Return the response status code
     *
     * @return the response status code
     */
    public int getStatusCode() {
        return response.getStatus().code();
    }

    /**
     * Return the response status text
     *
     * @return the response status text
     */
    public String getStatusText() {
        return response.getStatus().reasonPhrase();
    }

    @Override
    public String getProtocolName() {
        return response.getProtocolVersion().protocolName();
    }

    @Override
    public int getProtocolMajorVersion() {
        return response.getProtocolVersion().majorVersion();
    }

    @Override
    public int getProtocolMinorVersion() {
        return response.getProtocolVersion().minorVersion();
    }

    @Override
    public String getProtocolText() {
        return response.getProtocolVersion().text();
    }
}
