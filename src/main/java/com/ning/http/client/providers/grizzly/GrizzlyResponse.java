/*
 * Copyright (c) 2012-2015 Sonatype, Inc. All rights reserved.
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

import static com.ning.http.util.MiscUtils.isNonEmpty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http.Cookies;
import org.glassfish.grizzly.http.CookiesBuilder;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.utils.BufferInputStream;
import org.glassfish.grizzly.utils.Charsets;

import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.ResponseBase;
import com.ning.http.client.cookie.Cookie;
import org.glassfish.grizzly.http.HttpResponsePacket;

/**
 * {@link com.ning.http.client.HttpResponseBodyPart} implementation using the Grizzly 2.0 HTTP client
 * codec.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class GrizzlyResponse extends ResponseBase {

    private final Buffer responseBody;
    private final HttpResponsePacket httpResponsePacket;

    // ------------------------------------------------------------ Constructors


    public GrizzlyResponse(final HttpResponsePacket httpResponsePacket,
                           final HttpResponseStatus status,
                           final HttpResponseHeaders headers,
                           final List<HttpResponseBodyPart> bodyParts) {

        super(status, headers, bodyParts);

        this.httpResponsePacket = httpResponsePacket;
        
        if (isNonEmpty(bodyParts)) {
            if (bodyParts.size() == 1) {
                responseBody = ((GrizzlyResponseBodyPart) bodyParts.get(0)).getBodyBuffer();
            } else {
                final Buffer firstBuffer = ((GrizzlyResponseBodyPart) bodyParts.get(0)).getBodyBuffer();
                final MemoryManager mm = httpResponsePacket.getRequest().getConnection().getMemoryManager();
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


    @Override
    public InputStream getResponseBodyAsStream() throws IOException {

        return new BufferInputStream(responseBody);

    }


    @Override
    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {

        final int len = Math.min(responseBody.remaining(), maxLength);
        final int pos = responseBody.position();
        return responseBody.toStringContent(getCharset(charset), pos, len + pos);

    }


    @Override
    public String getResponseBody(String charset) throws IOException {

        return responseBody.toStringContent(getCharset(charset));

    }


    @Override
    public String getResponseBodyExcerpt(int maxLength) throws IOException {

        // TODO FIX NULL
        return getResponseBodyExcerpt(maxLength, null);

    }


    @Override
    public String getResponseBody() throws IOException {

        return getResponseBody(null);

    }


    @Override
    public byte[] getResponseBodyAsBytes() throws IOException {
        final byte[] responseBodyBytes = new byte[responseBody.remaining()];
        final int origPos = responseBody.position();
        responseBody.get(responseBodyBytes);
        responseBody.position(origPos);
        return responseBodyBytes;
    }

    @Override
    public ByteBuffer getResponseBodyAsByteBuffer() throws IOException {
        return responseBody.toByteBuffer();
    }

    @Override
    protected List<Cookie> buildCookies() {
        List<String> values = headers.getHeaders().get("set-cookie");
        if (isNonEmpty(values)) {
            CookiesBuilder.ServerCookiesBuilder builder =
                new CookiesBuilder.ServerCookiesBuilder(false, true);
            for (String header : values) {
                builder.parse(header);
            }
            return convertCookies(builder.build());

        } else {
            return Collections.emptyList();
        }
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
            charsetLocal = httpResponsePacket.getCharacterEncoding();
        }

        return charsetLocal == null ?
                Charsets.ASCII_CHARSET :
                Charsets.lookupCharset(charsetLocal);
    }
}