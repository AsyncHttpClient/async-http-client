/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.providers.jdk;

import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

/**
 * A class that represent the HTTP response' status line (code + text)
 */
public class ResponseStatus extends HttpResponseStatus {

    private final HttpURLConnection urlConnection;

    public ResponseStatus(URI uri, HttpURLConnection urlConnection) {
        super(uri);
        this.urlConnection = urlConnection;
    }

    public Response prepareResponse(HttpResponseHeaders headers, List<HttpResponseBodyPart> bodyParts) {
        return new JDKResponse(this, headers, bodyParts);
    }

    /**
     * Return the response status code
     *
     * @return the response status code
     */
    public int getStatusCode() {
        try {
            return urlConnection.getResponseCode();
        } catch (IOException e) {
            return 500;
        }
    }

    /**
     * Return the response status text
     *
     * @return the response status text
     */
    public String getStatusText() {
        try {
            return urlConnection.getResponseMessage();
        } catch (IOException e) {
            return "Internal Error";
        }
    }

    @Override
    public String getProtocolName() {
        return "http";
    }

    @Override
    public int getProtocolMajorVersion() {
        return 1;
    }

    @Override
    public int getProtocolMinorVersion() {
        return 1; //TODO
    }

    @Override
    public String getProtocolText() {
        return ""; //TODO
    }

}