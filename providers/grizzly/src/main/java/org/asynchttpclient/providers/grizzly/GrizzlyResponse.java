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

package org.asynchttpclient.providers.grizzly;

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.providers.ResponseBase;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http.Cookies;
import org.glassfish.grizzly.http.CookiesBuilder.ServerCookiesBuilder;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.utils.BufferInputStream;
import org.glassfish.grizzly.utils.Charsets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link org.asynchttpclient.HttpResponseBodyPart} implementation using the Grizzly 2.0 HTTP client
 * codec.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class GrizzlyResponse extends ResponseBase {

    private Buffer responseBody;
    private boolean initialized;

    // ------------------------------------------------------------ Constructors

    public GrizzlyResponse(final HttpResponseStatus status, final HttpResponseHeaders headers, final List<HttpResponseBodyPart> bodyParts) {
        super(status, headers, bodyParts);
    }

    // --------------------------------------------------- Methods from Response

    /**
     * {@inheritDoc}
     */
    public InputStream getResponseBodyAsStream() throws IOException {
        return new BufferInputStream(getResponseBody0());
    }

    /**
     * {@inheritDoc}
     */
    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {
        charset = calculateCharset(charset);
        final Buffer responseBody = getResponseBody0();
        final int len = Math.min(responseBody.remaining(), maxLength);
        final int pos = responseBody.position();
        return responseBody.toStringContent(getCharset(charset), pos, len + pos);
    }

    /**
     * {@inheritDoc}
     */
    public String getResponseBody(String charset) throws IOException {
        return getResponseBody0().toStringContent(getCharset(charset));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] getResponseBodyAsBytes() throws IOException {
        final Buffer responseBody = getResponseBody0();
        final byte[] responseBodyBytes = new byte[responseBody.remaining()];
        final int origPos = responseBody.position();
        responseBody.get(responseBodyBytes);
        responseBody.position(origPos);
        return responseBodyBytes;
    }

    @Override
    public ByteBuffer getResponseBodyAsByteBuffer() throws IOException {
        return ByteBuffer.wrap(getResponseBodyAsBytes());
    }

    /**
     * {@inheritDoc}
     */
    public String getResponseBodyExcerpt(int maxLength) throws IOException {
        return getResponseBodyExcerpt(maxLength, null);
    }

    /**
     * {@inheritDoc}
     */
    public String getResponseBody() throws IOException {
        return getResponseBody(null);
    }

    /**
     * @return the response body as a Grizzly {@link Buffer}.
     */
    @SuppressWarnings("UnusedDeclaration")
    public Buffer getResponseBodyAsBuffer() {
        return getResponseBody0();
    }

    /**
     * {@inheritDoc}
     */
    public List<Cookie> buildCookies() {

        List<String> values = headers.getHeaders().get(Header.SetCookie.toString());
        if (isNonEmpty(values)) {
            ServerCookiesBuilder builder = new ServerCookiesBuilder(false, true);
            for (int i = 0, len = values.size(); i < len; i++) {
                builder.parse(values.get(i));
            }
            return convertCookies(builder.build());

        } else {
            return Collections.unmodifiableList(Collections.<Cookie> emptyList());
        }
    }

    // --------------------------------------------------------- Private Methods

    private List<Cookie> convertCookies(Cookies cookies) {

        final org.glassfish.grizzly.http.Cookie[] grizzlyCookies = cookies.get();
        List<Cookie> convertedCookies = new ArrayList<Cookie>(grizzlyCookies.length);
        for (int i = 0, len = grizzlyCookies.length; i < len; i++) {
            org.glassfish.grizzly.http.Cookie gCookie = grizzlyCookies[i];
            convertedCookies.add(new Cookie(gCookie.getName(), gCookie.getValue(), gCookie.getValue(), gCookie.getDomain(), gCookie
                    .getPath(), -1L, gCookie.getMaxAge(), gCookie.isSecure(), gCookie.isHttpOnly()));
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

    private synchronized Buffer getResponseBody0() {
        if (!initialized) {
            if (isNonEmpty(bodyParts)) {
                if (bodyParts.size() == 1) {
                    responseBody = ((GrizzlyResponseBodyPart) bodyParts.get(0)).getBodyBuffer();
                } else {
                    final Buffer firstBuffer = ((GrizzlyResponseBodyPart) bodyParts.get(0)).getBodyBuffer();
                    final MemoryManager<?> mm = MemoryManager.DEFAULT_MEMORY_MANAGER;
                    Buffer constructedBodyBuffer = firstBuffer;
                    for (int i = 1, len = bodyParts.size(); i < len; i++) {
                        constructedBodyBuffer = Buffers.appendBuffers(mm, constructedBodyBuffer,
                                ((GrizzlyResponseBodyPart) bodyParts.get(i)).getBodyBuffer());
                    }
                    responseBody = constructedBodyBuffer;
                }
            } else {
                responseBody = Buffers.EMPTY_BUFFER;
            }
            initialized = true;
        }
        return responseBody;
    }
}
