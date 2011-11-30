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
package com.ning.http.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Represents the asynchronous HTTP response callback for an {@link com.ning.http.client.AsyncCompletionHandler}
 */
public interface Response {
    /**
     * Returns the status code for the request.
     *
     * @return The status code
     */
    public int getStatusCode();

    /**
     * Returns the status text for the request.
     *
     * @return The status text
     */
    public String getStatusText();

    /**
     * Return the entire response body as a byte[].
     *
     * @return the entire response body as a byte[].
     * @throws IOException
     */
    public byte[] getResponseBodyAsBytes() throws IOException;

    /**
     * Returns an input stream for the response body. Note that you should not try to get this more than once,
     * and that you should not close the stream.
     *
     * @return The input stream
     * @throws java.io.IOException
     */
    public InputStream getResponseBodyAsStream() throws IOException;

    /**
     * Returns the first maxLength bytes of the response body as a string. Note that this does not check
     * whether the content type is actually a textual one, but it will use the charset if present in the content
     * type header.
     *
     * @param maxLength The maximum number of bytes to read
     * @param charset   the charset to use when decoding the stream
     * @return The response body
     * @throws java.io.IOException
     */
    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException;

    /**
     * Return the entire response body as a String.
     *
     * @param charset the charset to use when decoding the stream
     * @return the entire response body as a String.
     * @throws IOException
     */
    public String getResponseBody(String charset) throws IOException;

    /**
     * Returns the first maxLength bytes of the response body as a string. Note that this does not check
     * whether the content type is actually a textual one, but it will use the charset if present in the content
     * type header.
     *
     * @param maxLength The maximum number of bytes to read
     * @return The response body
     * @throws java.io.IOException
     */
    public String getResponseBodyExcerpt(int maxLength) throws IOException;

    /**
     * Return the entire response body as a String.
     *
     * @return the entire response body as a String.
     * @throws IOException
     */
    public String getResponseBody() throws IOException;

    /**
     * Return the request {@link URI}. Note that if the request got redirected, the value of the {@link URI} will be
     * the last valid redirect url.
     *
     * @return the request {@link URI}.
     * @throws MalformedURLException
     */
    public URI getUri() throws MalformedURLException;

    /**
     * Return the content-type header value.
     *
     * @return the content-type header value.
     */
    public String getContentType();

    /**
     * Return the response header
     *
     * @return the response header
     */
    public String getHeader(String name);

    /**
     * Return a {@link List} of the response header value.
     *
     * @return the response header
     */
    public List<String> getHeaders(String name);

    public FluentCaseInsensitiveStringsMap getHeaders();

    /**
     * Return true if the response redirects to another object.
     *
     * @return True if the response redirects to another object.
     */
    boolean isRedirected();

    /**
     * Subclasses SHOULD implement toString() in a way that identifies the request for logging.
     *
     * @return The textual representation
     */
    public String toString();

    /**
     * Return the list of {@link Cookie}.
     */
    public List<Cookie> getCookies();

    /**
     * Return true if the response's status has been computed by an {@link AsyncHandler}
     *
     * @return true if the response's status has been computed by an {@link AsyncHandler}
     */
    public boolean hasResponseStatus();

    /**
     * Return true if the response's headers has been computed by an {@link AsyncHandler} It will return false if the
     * either {@link com.ning.http.client.AsyncHandler#onStatusReceived(HttpResponseStatus)}
     * or {@link AsyncHandler#onHeadersReceived(HttpResponseHeaders)} returned {@link com.ning.http.client.AsyncHandler.STATE#ABORT}
     *
     * @return true if the response's headers has been computed by an {@link AsyncHandler}
     */
    public boolean hasResponseHeaders();

    /**
     * Return true if the response's body has been computed by an {@link AsyncHandler}. It will return false if the
     * either {@link com.ning.http.client.AsyncHandler#onStatusReceived(HttpResponseStatus)}
     * or {@link AsyncHandler#onHeadersReceived(HttpResponseHeaders)} returned {@link com.ning.http.client.AsyncHandler.STATE#ABORT}
     *
     * @return true if the response's body has been computed by an {@link AsyncHandler}
     */
    public boolean hasResponseBody();


    public static class ResponseBuilder {
        private final Collection<HttpResponseBodyPart> bodies =
                Collections.synchronizedCollection(new ArrayList<HttpResponseBodyPart>());
        private HttpResponseStatus status;
        private HttpResponseHeaders headers;

        /**
         * Accumulate {@link HttpContent} in order to build a {@link Response}
         *
         * @param httpContent {@link HttpContent}
         * @return this
         */
        public ResponseBuilder accumulate(HttpContent httpContent) {
            if (httpContent instanceof HttpResponseStatus) {
                status = (HttpResponseStatus) httpContent;
            } else if (httpContent instanceof HttpResponseHeaders) {
                headers = (HttpResponseHeaders) httpContent;
            } else if (httpContent instanceof HttpResponseBodyPart) {
                bodies.add((HttpResponseBodyPart) httpContent);
            }
            return this;
        }

        /**
         * Build a {@link Response} instance
         *
         * @return a {@link Response} instance
         */
        public Response build() {
            return status == null ? null : status.provider().prepareResponse(status, headers, bodies);
        }

        /**
         * Reset the internal state of this builder.
         */
        public void reset() {
            bodies.clear();
            status = null;
            headers = null;
        }
    }

}