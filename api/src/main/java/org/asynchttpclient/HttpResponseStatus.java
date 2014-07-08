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
package org.asynchttpclient;

import java.util.List;

import org.asynchttpclient.uri.UriComponents;

/**
 * A class that represent the HTTP response' status line (code + text)
 */
public abstract class HttpResponseStatus {

    private final UriComponents uri;
    protected final AsyncHttpClientConfig config;

    public HttpResponseStatus(UriComponents uri, AsyncHttpClientConfig config) {
        this.uri = uri;
        this.config = config;
    }

    /**
     * Return the request {@link UriComponents}
     * 
     * @return the request {@link UriComponents}
     */
    public final UriComponents getUri() {
        return uri;
    }

    /**
     * Prepare a {@link Response}
     *
     * @param headers   {@link HttpResponseHeaders}
     * @param bodyParts list of {@link HttpResponseBodyPart}
     * @param config the client config
     * @return a {@link Response}
     */
    public abstract Response prepareResponse(HttpResponseHeaders headers, List<HttpResponseBodyPart> bodyParts);

    /**
     * Return the response status code
     * 
     * @return the response status code
     */
    public abstract int getStatusCode();

    /**
     * Return the response status text
     * 
     * @return the response status text
     */
    public abstract String getStatusText();

    /**
     * Protocol name from status line.
     * 
     * @return Protocol name.
     */
    public abstract String getProtocolName();

    /**
     * Protocol major version.
     * 
     * @return Major version.
     */
    public abstract int getProtocolMajorVersion();

    /**
     * Protocol minor version.
     * 
     * @return Minor version.
     */
    public abstract int getProtocolMinorVersion();

    /**
     * Full protocol name + version
     * 
     * @return protocol name + version
     */
    public abstract String getProtocolText();
}
