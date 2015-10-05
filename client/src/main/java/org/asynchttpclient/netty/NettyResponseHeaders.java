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
package org.asynchttpclient.netty;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;

import java.util.Map;

import org.asynchttpclient.HttpResponseHeaders;

/**
 * A class that represent the HTTP headers.
 */
public class NettyResponseHeaders extends HttpResponseHeaders {

    private final HttpHeaders responseHeaders;
    private final HttpHeaders trailingHeaders;
    private final HttpHeaders headers;

    public NettyResponseHeaders(HttpHeaders responseHeaders) {
        this(responseHeaders, null);
    }

    public NettyResponseHeaders(HttpHeaders responseHeaders, HttpHeaders traillingHeaders) {
        super(traillingHeaders != null);
        this.responseHeaders = responseHeaders;
        this.trailingHeaders = traillingHeaders;
        headers = computerHeaders();
    }

    private HttpHeaders computerHeaders() {
        HttpHeaders h = new DefaultHttpHeaders();
        for (Map.Entry<String, String> header : responseHeaders) {
            h.add(header.getKey(), header.getValue());
        }

        if (trailingHeaders != null) {
            for (Map.Entry<String, String> header : trailingHeaders) {
                h.add(header.getKey(), header.getValue());
            }
        }

        return h;
    }

    /**
     * Return the HTTP header
     * 
     * @return an {@link org.asynchttpclient.FluentCaseInsensitiveStringsMap}
     */
    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }
}
