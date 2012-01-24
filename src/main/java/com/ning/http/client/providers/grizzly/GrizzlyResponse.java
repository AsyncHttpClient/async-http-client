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

import com.ning.http.client.Cookie;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import com.ning.http.util.AsyncHttpProviderUtils;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http.CookiesBuilder;
import org.glassfish.grizzly.utils.Charsets;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.utils.BufferInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * {@link com.ning.http.client.HttpResponseBodyPart} implementation using the Grizzly 2.0 HTTP client
 * codec.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class GrizzlyResponse implements Response {

    private final HttpResponseStatus status;
    private final HttpResponseHeaders headers;
    private final Collection<HttpResponseBodyPart> bodyParts;
    private final Buffer responseBody;

    private List<Cookie> cookies;


    // ------------------------------------------------------------ Constructors


    public GrizzlyResponse(final HttpResponseStatus status,
                           final HttpResponseHeaders headers,
                           final Collection<HttpResponseBodyPart> bodyParts) {

        this.status = status;
        this.headers = headers;
        this.bodyParts = bodyParts;

        if (bodyParts != null && !bodyParts.isEmpty()) {
            HttpResponseBodyPart[] parts =
                    bodyParts.toArray(new HttpResponseBodyPart[bodyParts.size()]);
            if (parts.length == 1) {
                responseBody = ((GrizzlyResponseBodyPart) parts[0]).getBodyBuffer();
            } else {
                final Buffer firstBuffer = ((GrizzlyResponseBodyPart) parts[0]).getBodyBuffer();
                final MemoryManager mm = MemoryManager.DEFAULT_MEMORY_MANAGER;
                Buffer constructedBodyBuffer = firstBuffer;
                for (int i = 1, len = parts.length; i < len; i++) {
                    constructedBodyBuffer =
                            Buffers.appendBuffers(mm,
                                    constructedBodyBuffer,
                                    ((GrizzlyResponseBodyPart) parts[i]).getBodyBuffer());
                }
                responseBody = constructedBodyBuffer;
            }
        } else {
            responseBody = Buffers.EMPTY_BUFFER;
        }

    }


    // --------------------------------------------------- Methods from Response


    /**
     * {@inheritDoc}
     */
    public int getStatusCode() {

        return status.getStatusCode();

    }


    /**
     * {@inheritDoc}
     */
    public String getStatusText() {

        return status.getStatusText();

    }


    /**
     * {@inheritDoc}
     */
    public InputStream getResponseBodyAsStream() throws IOException {

        return new BufferInputStream(responseBody);

    }


    /**
     * {@inheritDoc}
     */
    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {

        final int len = Math.min(responseBody.remaining(), maxLength);
        final int pos = responseBody.position();
        return responseBody.toStringContent(getCharset(charset), pos, len + pos);

    }


    /**
     * {@inheritDoc}
     */
    public String getResponseBody(String charset) throws IOException {

        return responseBody.toStringContent(getCharset(charset));

    }


    /**
     * {@inheritDoc}
     */
    public String getResponseBodyExcerpt(int maxLength) throws IOException {

        // TODO FIX NULL
        return getResponseBodyExcerpt(maxLength, null);

    }


    /**
     * {@inheritDoc}
     */
    public String getResponseBody() throws IOException {

        return getResponseBody(Charsets.DEFAULT_CHARACTER_ENCODING);

    }


    /**
     * {@inheritDoc}
     */
    public byte[] getResponseBodyAsBytes() throws IOException {

        return getResponseBody().getBytes(Charsets.DEFAULT_CHARACTER_ENCODING);

    }


    /**
     * {@inheritDoc}
     */
    public URI getUri() throws MalformedURLException {

        return status.getUrl();

    }


    /**
     * {@inheritDoc}
     */
    public String getContentType() {

        return headers.getHeaders().getFirstValue("Content-Type");

    }


    /**
     * {@inheritDoc}
     */
    public String getHeader(String name) {

        return headers.getHeaders().getFirstValue(name);

    }


    /**
     * {@inheritDoc}
     */
    public List<String> getHeaders(String name) {

        return headers.getHeaders().get(name);

    }


    /**
     * {@inheritDoc}
     */
    public FluentCaseInsensitiveStringsMap getHeaders() {

        return headers.getHeaders();

    }


    /**
     * {@inheritDoc}
     */
    public boolean isRedirected() {

        return between(status.getStatusCode(), 300, 399);

    }


    /**
     * {@inheritDoc}
     */
    public List<Cookie> getCookies() {

        if (headers == null) {
            return Collections.emptyList();
        }

        if (cookies == null) {
            List<String> values = headers.getHeaders().get("set-cookie");
            if (values != null && !values.isEmpty()) {
                CookiesBuilder.ServerCookiesBuilder builder =
                    new CookiesBuilder.ServerCookiesBuilder(false);
                for (String header : values) {
                    builder.parse(header);
                }
                cookies = convertCookies(builder.build());

            } else {
                cookies = Collections.unmodifiableList(Collections.<Cookie>emptyList());
            }
        }
        return cookies;

    }


    /**
     * {@inheritDoc}
     */
    public boolean hasResponseStatus() {
        return (status != null);
    }


    /**
     * {@inheritDoc}
     */
    public boolean hasResponseHeaders() {
        return (headers != null && !headers.getHeaders().isEmpty());
    }


    /**
     * {@inheritDoc}
     */
    public boolean hasResponseBody() {
        return (bodyParts != null && !bodyParts.isEmpty());
    }


    // --------------------------------------------------------- Private Methods


    private List<Cookie> convertCookies(final List<org.glassfish.grizzly.http.Cookie> grizzlyCookies) {

        List<Cookie> cookies = new ArrayList<Cookie>(grizzlyCookies.size());
        for (org.glassfish.grizzly.http.Cookie gCookie : grizzlyCookies) {
            cookies.add(new Cookie(gCookie.getDomain(),
                                   gCookie.getName(),
                                   gCookie.getValue(),
                                   gCookie.getPath(),
                                   gCookie.getMaxAge(),
                                   gCookie.isSecure(),
                                   gCookie.getVersion()));
        }
        return Collections.unmodifiableList(cookies);

    }


    private Charset getCharset(final String charset) {

        String charsetLocal = charset;

        if (charsetLocal == null) {
            String contentType = getContentType();
            if (contentType != null) {
                charsetLocal = AsyncHttpProviderUtils.parseCharset(contentType);
            }
        }

        if (charsetLocal == null) {
            charsetLocal = Charsets.DEFAULT_CHARACTER_ENCODING;
        }

        return Charsets.lookupCharset(charsetLocal);

    }


    private boolean between(final int value,
                            final int lowerBound,
                            final int upperBound) {

        return (value >= lowerBound && value <= upperBound);

    }

}
