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

package com.ning.http.client.providers.grizzly;

import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.HttpResponseStatus;

import org.glassfish.grizzly.http.HttpResponsePacket;

import java.net.URI;

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
                                 final URI uri,
                                 final AsyncHttpProvider provider) {

        super(uri, provider);
        this.response = response;

    }


    // ----------------------------------------- Methods from HttpResponseStatus


    /**
     * {@inheritDoc}
     */
    @Override
    public int getStatusCode() {

        return response.getStatus();

    }


    /**
     * {@inheritDoc}
     */
    @Override
    public String getStatusText() {

        return response.getReasonPhrase();

    }


    /**
     * {@inheritDoc}
     */
    @Override
    public String getProtocolName() {

        return "http";

    }


    /**
     * {@inheritDoc}
     */
    @Override
    public int getProtocolMajorVersion() {

        return response.getProtocol().getMajorVersion();

    }


    /**
     * {@inheritDoc}
     */
    @Override
    public int getProtocolMinorVersion() {

        return response.getProtocol().getMinorVersion();

    }


    /**
     * {@inheritDoc}
     */
    @Override
    public String getProtocolText() {
        return response.getProtocolString();
    }

}
