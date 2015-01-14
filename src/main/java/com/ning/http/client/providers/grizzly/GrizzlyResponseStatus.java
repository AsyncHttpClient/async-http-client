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

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import com.ning.http.client.uri.Uri;

import java.util.List;

import org.glassfish.grizzly.http.HttpResponsePacket;

/**
 * {@link HttpResponseStatus} implementation using the Grizzly 2.0 HTTP client
 * codec.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class GrizzlyResponseStatus extends HttpResponseStatus {

    private final HttpResponsePacket response;


    // ------------------------------------------------------------ Constructors


    public GrizzlyResponseStatus(final HttpResponsePacket response,
                                 final Uri uri,
                                 final AsyncHttpClientConfig config) {

        super(uri, config);
        this.response = response;

    }


    // ----------------------------------------- Methods from HttpResponseStatus


    @Override
    public int getStatusCode() {

        return response.getStatus();

    }


    @Override
    public String getStatusText() {

        return response.getReasonPhrase();

    }


    @Override
    public String getProtocolName() {

        return "http";

    }


    @Override
    public int getProtocolMajorVersion() {

        return response.getProtocol().getMajorVersion();

    }


    @Override
    public int getProtocolMinorVersion() {

        return response.getProtocol().getMinorVersion();

    }


    @Override
    public String getProtocolText() {
        return response.getProtocolString();
    }

    @Override
    public Response prepareResponse(HttpResponseHeaders headers, List<HttpResponseBodyPart> bodyParts) {
        return new GrizzlyResponse(response, this, headers, bodyParts);
    }
}
