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
package com.ning.http.client.providers.apache;

import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.HttpResponseStatus;
import org.apache.commons.httpclient.HttpMethodBase;

import java.net.URI;

/**
 * A class that represent the HTTP response' status line (code + text)
 */
public class ApacheResponseStatus extends HttpResponseStatus {

    private final HttpMethodBase method;

    public ApacheResponseStatus(URI uri, HttpMethodBase method, AsyncHttpProvider provider) {
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