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
package com.ning.http.client.providers.netty.response;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import com.ning.http.client.uri.Uri;

import java.util.List;

import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * A class that represent the HTTP response' status line (code + text)
 */
public class NettyResponseStatus extends HttpResponseStatus {

    private final HttpResponse response;

    public NettyResponseStatus(Uri uri, AsyncHttpClientConfig config, HttpResponse response) {
        super(uri, config);
        this.response = response;
    }

    /**
     * Return the response status code
     *
     * @return the response status code
     */
    public int getStatusCode() {
        return response.getStatus().getCode();
    }

    /**
     * Return the response status text
     *
     * @return the response status text
     */
    public String getStatusText() {
        return response.getStatus().getReasonPhrase();
    }

    @Override
    public String getProtocolName() {
        return response.getProtocolVersion().getProtocolName();
    }

    @Override
    public int getProtocolMajorVersion() {
        return response.getProtocolVersion().getMajorVersion();
    }

    @Override
    public int getProtocolMinorVersion() {
        return response.getProtocolVersion().getMinorVersion();
    }

    @Override
    public String getProtocolText() {
        return response.getProtocolVersion().getText();
    }

    @Override
    public Response prepareResponse(HttpResponseHeaders headers, List<HttpResponseBodyPart> bodyParts) {
        return new NettyResponse(this, headers, bodyParts);
    }
}
