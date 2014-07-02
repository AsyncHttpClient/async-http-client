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
package com.ning.http.client.providers.netty;

import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.uri.UriComponents;

import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpResponse;

import java.util.Map;

/**
 * A class that represent the HTTP headers.
 */
public class ResponseHeaders extends HttpResponseHeaders {

    private final HttpChunkTrailer trailingHeaders;
    private final HttpResponse response;
    private final FluentCaseInsensitiveStringsMap headers;

    public ResponseHeaders(UriComponents uri, HttpResponse response, AsyncHttpProvider provider) {
        super(uri, provider, false);
        this.trailingHeaders = null;
        this.response = response;
        headers = computerHeaders();
    }

    public ResponseHeaders(UriComponents uri, HttpResponse response, AsyncHttpProvider provider, HttpChunkTrailer traillingHeaders) {
        super(uri, provider, true);
        this.trailingHeaders = traillingHeaders;
        this.response = response;
        headers = computerHeaders();
    }

    private FluentCaseInsensitiveStringsMap computerHeaders() {
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        for (Map.Entry<String, String> header: response.getHeaders()) {
           h.add(header.getKey(), header.getValue());
        }

        if (trailingHeaders != null) {
            for (Map.Entry<String, String> header:  trailingHeaders.getHeaders()) {
                h.add(header.getKey(), header.getValue());
            }
        }

        return h;
    }

    /**
     * Return the HTTP header
     *
     * @return an {@link com.ning.http.client.FluentCaseInsensitiveStringsMap}
     */
    @Override
    public FluentCaseInsensitiveStringsMap getHeaders() {
        return headers;
    }
}
