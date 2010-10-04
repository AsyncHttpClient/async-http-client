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
package com.ning.http.client.providers.jdk;

import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.HttpResponseStatus;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * A class that represent the HTTP response' status line (code + text)
 */
public class ResponseStatus extends HttpResponseStatus {

    private final HttpURLConnection urlConnection;

    public ResponseStatus(URI uri, HttpURLConnection urlConnection, AsyncHttpProvider<HttpURLConnection> provider) {
        super(uri, provider);
        this.urlConnection = urlConnection;
    }

    /**
     * Return the response status code
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