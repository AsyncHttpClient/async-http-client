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

import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.uri.UriComponents;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the asynchronous HTTP response callback for an {@link AsyncCompletionHandler}
 */
public interface Response {
    /**
     * Returns the status code for the request.
     * 
     * @return The status code
     */
    int getStatusCode();

    /**
     * Returns the status text for the request.
     * 
     * @return The status text
     */
    String getStatusText();

    /**
     * Return the entire response body as a byte[].
     * 
     * @return the entire response body as a byte[].
     * @throws IOException
     */
    byte[] getResponseBodyAsBytes() throws IOException;

    /**
     * Return the entire response body as a ByteBuffer.
     * 
     * @return the entire response body as a ByteBuffer.
     * @throws IOException
     */
    ByteBuffer getResponseBodyAsByteBuffer() throws IOException;

    /**
     * Returns an input stream for the response body. Note that you should not try to get this more than once, and that you should not close the stream.
     * 
     * @return The input stream
     * @throws java.io.IOException
     */
    InputStream getResponseBodyAsStream() throws IOException;

    /**
     * Returns the first maxLength bytes of the response body as a string. Note that this does not check whether the content type is actually a textual one, but it will use the
     * charset if present in the content type header.
     * 
     * @param maxLength
     *            The maximum number of bytes to read
     * @param charset
     *            the charset to use when decoding the stream
     * @return The response body
     * @throws java.io.IOException
     */
    String getResponseBodyExcerpt(int maxLength, String charset) throws IOException;

    /**
     * Return the entire response body as a String.
     * 
     * @param charset
     *            the charset to use when decoding the stream
     * @return the entire response body as a String.
     * @throws IOException
     */
    String getResponseBody(String charset) throws IOException;

    /**
     * Returns the first maxLength bytes of the response body as a string. Note that this does not check whether the content type is actually a textual one, but it will use the
     * charset if present in the content type header.
     * 
     * @param maxLength
     *            The maximum number of bytes to read
     * @return The response body
     * @throws java.io.IOException
     */
    String getResponseBodyExcerpt(int maxLength) throws IOException;

    /**
     * Return the entire response body as a String.
     * 
     * @return the entire response body as a String.
     * @throws IOException
     */
    String getResponseBody() throws IOException;

    /**
     * Return the request {@link UriComponents}. Note that if the request got redirected, the value of the {@link URI} will be the last valid redirect url.
     * 
     * @return the request {@link UriComponents}.
     */
    UriComponents getUri();

    /**
     * Return the content-type header value.
     * 
     * @return the content-type header value.
     */
    String getContentType();

    /**
     * Return the response header
     * 
     * @return the response header
     */
    String getHeader(String name);

    /**
     * Return a {@link List} of the response header value.
     * 
     * @return the response header
     */
    List<String> getHeaders(String name);

    FluentCaseInsensitiveStringsMap getHeaders();

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
    String toString();

    /**
     * Return the list of {@link Cookie}.
     */
    List<Cookie> getCookies();

    /**
     * Return true if the response's status has been computed by an {@link AsyncHandler}
     * 
     * @return true if the response's status has been computed by an {@link AsyncHandler}
     */
    boolean hasResponseStatus();

    /**
     * Return true if the response's headers has been computed by an {@link AsyncHandler} It will return false if the either
     * {@link AsyncHandler#onStatusReceived(HttpResponseStatus)} or {@link AsyncHandler#onHeadersReceived(HttpResponseHeaders)} returned {@link AsyncHandler.STATE#ABORT}
     * 
     * @return true if the response's headers has been computed by an {@link AsyncHandler}
     */
    boolean hasResponseHeaders();

    /**
     * Return true if the response's body has been computed by an {@link AsyncHandler}. It will return false if the either {@link AsyncHandler#onStatusReceived(HttpResponseStatus)}
     * or {@link AsyncHandler#onHeadersReceived(HttpResponseHeaders)} returned {@link AsyncHandler.STATE#ABORT}
     * 
     * @return true if the response's body has been computed by an {@link AsyncHandler}
     */
    boolean hasResponseBody();

    public static class ResponseBuilder {
        private final List<HttpResponseBodyPart> bodyParts = new ArrayList<HttpResponseBodyPart>();
        private HttpResponseStatus status;
        private HttpResponseHeaders headers;

        public ResponseBuilder accumulate(HttpResponseStatus status) {
            this.status = status;
            return this;
        }

        public ResponseBuilder accumulate(HttpResponseHeaders headers) {
            this.headers = headers;
            return this;
        }

        /**
         * @param bodyPart
         *            a body part (possibly empty, but will be filtered out)
         * @return this
         */
        public ResponseBuilder accumulate(HttpResponseBodyPart bodyPart) {
            if (bodyPart.length() > 0)
                bodyParts.add(bodyPart);
            return this;
        }

        /**
         * Build a {@link Response} instance
         * 
         * @return a {@link Response} instance
         */
        public Response build() {
            return status == null ? null : status.prepareResponse(headers, bodyParts);
        }

        /**
         * Reset the internal state of this builder.
         */
        public void reset() {
            bodyParts.clear();
            status = null;
            headers = null;
        }
    }
}
