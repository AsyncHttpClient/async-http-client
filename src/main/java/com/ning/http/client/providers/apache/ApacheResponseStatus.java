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
package com.ning.http.client.providers.apache;

import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.HttpResponseStatus;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethodBase;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 * A class that represent the HTTP response' status line (code + text)
 */
public class ApacheResponseStatus extends HttpResponseStatus {

    private final HttpMethodBase method;

    public ApacheResponseStatus(URI uri, HttpMethodBase method, AsyncHttpProvider<HttpClient> provider) {
        super(uri, provider);
        this.method = method;
    }

    /**
     * Return the response status code
     *
     * @return the response status code
     */
    public int getStatusCode() {
        return method.getStatusCode();
    }

    /**
     * Return the response status text
     *
     * @return the response status text
     */
    public String getStatusText() {
        return method.getStatusText();
    }

    @Override
    public String getProtocolName() {
        return method.getStatusLine().getHttpVersion();
    }

    @Override
    public int getProtocolMajorVersion() {
        return 1; //TODO
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