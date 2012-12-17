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
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.providers.ResponseBase;
import com.ning.http.util.AsyncHttpProviderUtils;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http.Cookies;
import org.glassfish.grizzly.http.CookiesBuilder;
import org.glassfish.grizzly.utils.Charsets;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.utils.BufferInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link com.ning.http.client.HttpResponseBodyPart} implementation using the Grizzly 2.0 HTTP client
 * codec.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class GrizzlyResponse extends ResponseBase {
    private final Buffer responseBody;

    // ------------------------------------------------------------ Constructors


    public GrizzlyResponse(final HttpResponseStatus status,
                           final HttpResponseHeaders headers,
                           final List<HttpResponseBodyPart> bodyParts) {
        super(status, headers, bodyParts);

        if (bodyParts != null && !bodyParts.isEmpty()) {
            if (bodyParts.size() == 1) {
                responseBody = ((GrizzlyResponseBodyPart) bodyParts.get(0)).getBodyBuffer();
            } else {
                final Buffer firstBuffer = ((GrizzlyResponseBodyPart) bodyParts.get(0)).getBodyBuffer();
                final MemoryManager<?> mm = MemoryManager.DEFAULT_MEMORY_MANAGER;
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
    public InputStream getResponseBodyAsStream() throws IOException {

        return new BufferInputStream(responseBody);

    }


    /**
     * {@inheritDoc}
     */
    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {
       charset = calculateCharset(charset);
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
    @Override
    public byte[] getResponseBodyAsBytes() throws IOException {
        final byte[] responseBodyBytes = new byte[responseBody.remaining()];
        final int origPos = responseBody.position();
        responseBody.get(responseBodyBytes);
        responseBody.position(origPos);
        return responseBodyBytes;
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

        return getResponseBody(Charsets.DEFAULT_CHARACTER_ENCODING);

    }

    /**
     * {@inheritDoc}
     */
    public List<Cookie> buildCookies() {

        List<String> values = headers.getHeaders().get("set-cookie");
        if (values != null && !values.isEmpty()) {
            CookiesBuilder.ServerCookiesBuilder builder = new CookiesBuilder.ServerCookiesBuilder(false);
            for (String header : values) {
                builder.parse(header);
            }
            return convertCookies(builder.build());

        } else {
        	return Collections.unmodifiableList(Collections.<Cookie>emptyList());
        }
    }

    // --------------------------------------------------------- Private Methods


    private List<Cookie> convertCookies(Cookies cookies) {

        final org.glassfish.grizzly.http.Cookie[] grizzlyCookies = cookies.get();
        List<Cookie> convertedCookies = new ArrayList<Cookie>(grizzlyCookies.length);
        for (org.glassfish.grizzly.http.Cookie gCookie : grizzlyCookies) {
            convertedCookies.add(new Cookie(gCookie.getDomain(),
                                   gCookie.getName(),
                                   gCookie.getValue(),
                                   gCookie.getPath(),
                                   gCookie.getMaxAge(),
                                   gCookie.isSecure(),
                                   gCookie.getVersion()));
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
