/*
 * Copyright (c) 2012-2015 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package com.ning.http.client.providers.grizzly;

import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseHeaders;

import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.utils.Charsets;

/**
 * {@link HttpResponseHeaders} implementation using the Grizzly 2.0 HTTP client
 * codec.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class GrizzlyResponseHeaders extends HttpResponseHeaders {

    private final FluentCaseInsensitiveStringsMap headers =
            new FluentCaseInsensitiveStringsMap();
    private final HttpResponsePacket response;
    private volatile boolean initialized;

    // ------------------------------------------------------------ Constructors


    public GrizzlyResponseHeaders(final HttpResponsePacket response) {

        this.response = response;

    }


    // ---------------------------------------- Methods from HttpResponseHeaders


    @Override
    public FluentCaseInsensitiveStringsMap getHeaders() {
        if (!initialized) {
            synchronized (headers) {
                if (!initialized) {
                    initialized = true;
                    final MimeHeaders headersLocal = response.getHeaders();
                    for (int i = 0; i < headersLocal.size(); i++) {
                        headers.add(headersLocal.getName(i).toString(Charsets.ASCII_CHARSET),
                                headersLocal.getValue(i).toString(Charsets.ASCII_CHARSET));
                    }
                }
            }
        }
        return headers;
    }



}
