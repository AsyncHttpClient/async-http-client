/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty;

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import io.netty.handler.codec.http.HttpHeaders;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.ResponseBase;
import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.cookie.CookieDecoder;

/**
 * Wrapper around the {@link org.asynchttpclient.Response} API.
 */
public class NettyResponse extends ResponseBase {

    public NettyResponse(HttpResponseStatus status,//
            HttpResponseHeaders headers,//
            List<HttpResponseBodyPart> bodyParts) {
        super(status, headers, bodyParts);
    }

    protected List<Cookie> buildCookies() {

        List<String> setCookieHeaders = headers.getHeaders().getAll(HttpHeaders.Names.SET_COOKIE2);

        if (!isNonEmpty(setCookieHeaders)) {
            setCookieHeaders = headers.getHeaders().getAll(HttpHeaders.Names.SET_COOKIE);
        }

        if (isNonEmpty(setCookieHeaders)) {
            List<Cookie> cookies = new ArrayList<>();
            for (String value : setCookieHeaders) {
                Cookie c = CookieDecoder.decode(value);
                if (c != null)
                    cookies.add(c);
            }
            return Collections.unmodifiableList(cookies);
        }

        return Collections.emptyList();
    }

    @Override
    public byte[] getResponseBodyAsBytes() {
        return getResponseBodyAsByteBuffer().array();
    }

    @Override
    public ByteBuffer getResponseBodyAsByteBuffer() {

        int length = 0;
        for (HttpResponseBodyPart part : bodyParts)
            length += part.length();

        ByteBuffer target = ByteBuffer.wrap(new byte[length]);
        for (HttpResponseBodyPart part : bodyParts)
            target.put(part.getBodyPartBytes());

        return target;
    }

    @Override
    public String getResponseBody() {
        return getResponseBody(null);
    }

    @Override
    public String getResponseBody(Charset charset) {
        return new String(getResponseBodyAsBytes(), computeCharset(charset));
    }

    @Override
    public InputStream getResponseBodyAsStream() {
        return new ByteArrayInputStream(getResponseBodyAsBytes());
    }
}
