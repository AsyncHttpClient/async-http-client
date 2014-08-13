/*
 * Copyright (c) 2012-2014 Sonatype, Inc. All rights reserved.
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

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
import org.asynchttpclient.uri.UriComponents;
import org.glassfish.grizzly.http.HttpResponsePacket;

import java.util.List;

/**
 * {@link HttpResponseStatus} implementation using the Grizzly 2.0 HTTP client
 * codec.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class GrizzlyResponseStatus extends HttpResponseStatus {

    private static final String PROTOCOL_NAME = "HTTP";
    private final int statusCode;
    private final String statusText;
    private final int majorVersion;
    private final int minorVersion;
    private final String protocolText;
    private final HttpResponsePacket response;
    
    // ------------------------------------------------------------ Constructors

    public GrizzlyResponseStatus(final HttpResponsePacket response, final UriComponents uri, AsyncHttpClientConfig config) {

        super(uri, config);
        statusCode = response.getStatus();
        statusText = response.getReasonPhrase();
        majorVersion = response.getProtocol().getMajorVersion();
        minorVersion = response.getProtocol().getMinorVersion();
        protocolText = response.getProtocolString();
        
        this.response = response;
    }

    // ----------------------------------------- Methods from HttpResponseStatus

    @Override
    public Response prepareResponse(HttpResponseHeaders headers, List<HttpResponseBodyPart> bodyParts) {
        return new GrizzlyResponse(this, headers, bodyParts);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getStatusText() {
        return statusText;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getProtocolName() {
        return PROTOCOL_NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getProtocolMajorVersion() {
        return majorVersion;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getProtocolMinorVersion() {
        return minorVersion;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getProtocolText() {
        return protocolText;
    }

    /**
     * @return internal Grizzly {@link HttpResponsePacket}
     */
    public HttpResponsePacket getResponse() {
        return response;
    }
}
