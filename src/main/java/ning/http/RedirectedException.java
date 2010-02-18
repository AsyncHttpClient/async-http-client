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
package ning.http;

import ning.http.client.Response;

import java.net.MalformedURLException;

/**
 * Gets thrown when a request is redirected.
 */
public class RedirectedException extends RuntimeException {
    private static final long serialVersionUID = -9054164427229522327L;

    private final int statusCode;
    private final String url;

    public RedirectedException(final int statusCode, final String url) {
        super();
        this.statusCode = statusCode;
        this.url = url;
    }

    public RedirectedException(final int statusCode, final String url, final String message) {
        super(message);
        this.statusCode = statusCode;
        this.url = url;
    }

    public RedirectedException(final Response response) throws MalformedURLException {
        this(response.getStatusCode(), response.getHeader("Location"));
    }

    public RedirectedException(final Response response, final String message) throws MalformedURLException {
        this(response.getStatusCode(), response.getHeader("Location"), message);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getUrl() {
        return url;
    }
}
