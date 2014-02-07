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

import static com.ning.http.util.MiscUtil.isNonEmpty;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http.Cookies;
import org.glassfish.grizzly.http.CookiesBuilder;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.utils.BufferInputStream;
import org.glassfish.grizzly.utils.Charsets;

import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import com.ning.http.client.cookie.Cookie;
import com.ning.http.util.AsyncHttpProviderUtils;

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
                           final List<HttpResponseBodyPart> bodyParts) {

        this.status = status;
        this.headers = headers;
        this.bodyParts = bodyParts;

        if (isNonEmpty(bodyParts)) {
            if (bodyParts.size() == 1) {
                responseBody = ((GrizzlyResponseBodyPart) bodyParts.get(0)).getBodyBuffer();
            } else {
                final Buffer firstBuffer = ((GrizzlyResponseBodyPart) bodyParts.get(0)).getBodyBuffer();
                final MemoryManager mm = MemoryManager.DEFAULT_MEMORY_MANAGER;
                Buffer constructedBodyBuffer = firstBuffer;
                for (int i = 1, len = bodyParts.size(); i < len; i++) {
                    constructedBodyBuffer =
                            Buffers.appendBuffers(mm,
                                    constructedBodyBuffer,
                                    ((GrizzlyResponseBodyPart) bodyParts.get(i)).getBodyBuffer());
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

        return getResponseBody(null);

    }


    /**
     * {@inheritDoc}
     */
    public byte[] getResponseBodyAsBytes() throws IOException {
        final byte[] responseBodyBytes = new byte[responseBody.remaining()];
        final int origPos = responseBody.position();
        responseBody.get(responseBodyBytes);
        responseBody.position(origPos);
        return responseBodyBytes;
    }

    public ByteBuffer getResponseBodyAsByteBuffer() throws IOException {
        return responseBody.toByteBuffer();
    }

    /**
     * @return the response body as a Grizzly {@link Buffer}.
     *
     * @since 1.7.11.
     */
    @SuppressWarnings("UnusedDeclaration")
    private Buffer getResponseBodyAsBuffer() {
        return responseBody;
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
        switch (status.getStatusCode()) {
        case 301:
        case 302:
        case 303:
        case 307:
        case 308:
            return true;
        default:
            return false;
        }
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
            if (isNonEmpty(values)) {
                CookiesBuilder.ServerCookiesBuilder builder =
                    new CookiesBuilder.ServerCookiesBuilder(false, true);
                for (String header : values) {
                    builder.parse(header);
                }
                cookies = convertCookies(builder.build());

            } else {
                cookies = Collections.emptyList();
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
        return headers != null && !headers.getHeaders().isEmpty();
    }


    /**
     * {@inheritDoc}
     */
    public boolean hasResponseBody() {
        return isNonEmpty(bodyParts);
    }


    // --------------------------------------------------------- Private Methods


    private List<Cookie> convertCookies(Cookies cookies) {

        final org.glassfish.grizzly.http.Cookie[] grizzlyCookies = cookies.get();
        List<Cookie> convertedCookies = new ArrayList<Cookie>(grizzlyCookies.length);
        for (org.glassfish.grizzly.http.Cookie gCookie : grizzlyCookies) {
            convertedCookies.add(new Cookie(gCookie.getName(),
                                   gCookie.getValue(),
                                   gCookie.getValue(),
                                   gCookie.getDomain(),
                                   gCookie.getPath(),
                                   -1L,
                                   gCookie.getMaxAge(),
                                   gCookie.isSecure(),
                                   false));
        }
        return Collections.unmodifiableList(convertedCookies);

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
}