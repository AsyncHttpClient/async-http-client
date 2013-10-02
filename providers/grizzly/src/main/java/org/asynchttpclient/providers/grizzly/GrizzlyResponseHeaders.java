/*
 * Copyright (c) 2012 Sonatype, Inc. All rights reserved.
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

package org.asynchttpclient.providers.grizzly;

import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.HttpResponseHeaders;

import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.util.MimeHeaders;


/**
 * {@link HttpResponseHeaders} implementation using the Grizzly 2.0 HTTP client
 * codec.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
class GrizzlyResponseHeaders extends HttpResponseHeaders {

    private FluentCaseInsensitiveStringsMap headers;
    private MimeHeaders grizzlyHeaders;


    // ------------------------------------------------------------ Constructors


    public GrizzlyResponseHeaders(final HttpResponsePacket response) {

        grizzlyHeaders = new MimeHeaders();
        grizzlyHeaders.copyFrom(response.getHeaders());

    }


    // ---------------------------------------- Methods from HttpResponseHeaders


    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized FluentCaseInsensitiveStringsMap getHeaders() {
        if (headers == null) {
            headers = new FluentCaseInsensitiveStringsMap();
            for (String name : grizzlyHeaders.names()) {
                for (String header : grizzlyHeaders.values(name)) {
                    headers.add(name, header);
                }
            }
        }
        return headers;
    }


    @Override
    public String toString() {
        return getHeaders().toString();
    }
}
