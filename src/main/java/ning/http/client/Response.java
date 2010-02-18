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
package ning.http.client;

import ning.http.io.MoreDataThanExpectedException;
import ning.http.url.Url;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.List;

/**
 * Represents the http response.
 */
public interface Response {
    /**
     * Returns the status code for the request.
     *
     * @return The status code, use the related {@link javax.servlet.http.HttpServletRequest} constants
     */
    public int getStatusCode();

    /**
     * Returns the status text for the request.
     *
     * @return The status text
     */
    public String getStatusText();

    /**
     * Returns an input stream for the response body. Note that you should not try to get this more than once,
     * and that you should not close the stream.
     *
     * @return The input stream
     */
    public InputStream getResponseBodyAsStream() throws IOException;

    /**
     * Returns the first maxLength bytes of the response body as a string. Note that this does not check
     * whether the content type is actually a textual one, but it will use the charset if present in the content
     * type header.
     * Note that this method will not throw a {@link MoreDataThanExpectedException} exception if there is more
     * data in the response than maxLength. Rather, it will consume the whole response, but only return the first
     * maxLength bytes.
     *
     * @param maxLength The maximum number of bytes to read
     * @return The response body
     */
    public String getResponseBodyExcerpt(int maxLength) throws IOException;

    public Url getUrl() throws MalformedURLException;

    public String getContentType();

    public String getHeader(String name);

    public List<String> getHeaders(String name);

    public Headers getHeaders();

    /**
     * Return true if the response redirects to another object.
     *
     * @return True if the response redirects to another object.
     */
    boolean isRedirected();


    /**
     * Subclasses SHOULD implement toString() in a way that identifies the request for logging.
     * @return The textual representation
     */
    public String toString();
}