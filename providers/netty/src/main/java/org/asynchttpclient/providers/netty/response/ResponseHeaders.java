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
package org.asynchttpclient.providers.netty.response;

import io.netty.handler.codec.http.HttpHeaders;

import java.net.URI;
import java.util.Map;

import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.HttpResponseHeaders;

/**
 * A class that represent the HTTP headers.
 */
public class ResponseHeaders extends HttpResponseHeaders {

    private final HttpHeaders responseHeaders;
    private final HttpHeaders trailingHeaders;
    private final FluentCaseInsensitiveStringsMap headers;

    // FIXME unused AsyncHttpProvider provider
    public ResponseHeaders(URI uri, HttpHeaders responseHeaders) {
        this(uri, responseHeaders, null);
    }

    public ResponseHeaders(URI uri,HttpHeaders responseHeaders, HttpHeaders traillingHeaders) {
        super(traillingHeaders != null);
        this.responseHeaders = responseHeaders;
        this.trailingHeaders = traillingHeaders;
        headers = computerHeaders();
    }

    private FluentCaseInsensitiveStringsMap computerHeaders() {
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        for (Map.Entry<String, String> header: responseHeaders) {
            h.add(header.getKey(), header.getValue());
        }

        if (trailingHeaders != null) {
            for (Map.Entry<String, String> header:  trailingHeaders) {
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
    public FluentCaseInsensitiveStringsMap getHeaders() {
        return headers;
    }
}
