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
package org.asynchttpclient.providers.netty4;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Request;
import org.asynchttpclient.Response;

import io.netty.handler.codec.http.HttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * A class that represent the HTTP response' status line (code + text)
 */
public class ResponseStatus extends HttpResponseStatus {
    
    private static final AsyncHttpProvider fakeProvider = new AsyncHttpProvider() {
        public <T> ListenableFuture<T> execute(Request request, AsyncHandler<T> handler) throws IOException {
            throw new UnsupportedOperationException("Mocked, should be refactored");
        }

        public void close() {
            throw new UnsupportedOperationException("Mocked, should be refactored");
        }

        public Response prepareResponse(HttpResponseStatus status,
                                        HttpResponseHeaders headers,
                                        List<HttpResponseBodyPart> bodyParts) {
            return new NettyResponse(status, headers, bodyParts);
        }
    };

    private final HttpResponse response;

    // FIXME ResponseStatus should have an abstract prepareResponse(headers, bodyParts) method instead of being passed the provider!
    public ResponseStatus(URI uri, HttpResponse response) {
        super(uri, fakeProvider);
        this.response = response;
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
