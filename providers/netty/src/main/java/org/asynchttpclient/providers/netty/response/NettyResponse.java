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
package org.asynchttpclient.providers.netty.response;

import static org.asynchttpclient.util.AsyncHttpProviderUtils.contentToBytes;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import io.netty.handler.codec.http.HttpHeaders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.cookie.CookieDecoder;
import org.asynchttpclient.date.TimeConverter;
import org.asynchttpclient.providers.ResponseBase;

/**
 * Wrapper around the {@link org.asynchttpclient.Response} API.
 */
public class NettyResponse extends ResponseBase {

    private final TimeConverter timeConverter;

    public NettyResponse(HttpResponseStatus status,//
            HttpResponseHeaders headers,//
            List<HttpResponseBodyPart> bodyParts,//
            TimeConverter timeConverter) {
        super(status, headers, bodyParts);
        this.timeConverter = timeConverter;
    }

    @Override
    public String getResponseBodyExcerpt(int maxLength) throws IOException {
        return getResponseBodyExcerpt(maxLength, null);
    }

    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {
        // should be fine; except that it may split multi-byte chars (last char may become '?')
        charset = calculateCharset(charset);
        byte[] b = contentToBytes(bodyParts, maxLength);
        return new String(b, charset);
    }

    protected List<Cookie> buildCookies() {

        List<String> setCookieHeaders = headers.getHeaders().get(HttpHeaders.Names.SET_COOKIE2);

        if (!isNonEmpty(setCookieHeaders)) {
            setCookieHeaders = headers.getHeaders().get(HttpHeaders.Names.SET_COOKIE);
        }

        if (isNonEmpty(setCookieHeaders)) {
            List<Cookie> cookies = new ArrayList<Cookie>();
            for (String value : setCookieHeaders) {
                Cookie c = CookieDecoder.decode(value, timeConverter);
                if (c != null)
                    cookies.add(c);
            }
            return Collections.unmodifiableList(cookies);
        }

        return Collections.emptyList();
    }

    @Override
    public byte[] getResponseBodyAsBytes() throws IOException {
        return getResponseBodyAsByteBuffer().array();
    }

    @Override
    public ByteBuffer getResponseBodyAsByteBuffer() throws IOException {

        int length = 0;
        for (HttpResponseBodyPart part : bodyParts)
            length += part.length();

        ByteBuffer target = ByteBuffer.wrap(new byte[length]);
        for (HttpResponseBodyPart part : bodyParts)
            target.put(part.getBodyPartBytes());

        return target;
    }

    @Override
    public String getResponseBody() throws IOException {
        return getResponseBody(null);
    }

    @Override
    public String getResponseBody(String charset) throws IOException {
        return new String(getResponseBodyAsBytes(), calculateCharset(charset));
    }

    @Override
    public InputStream getResponseBodyAsStream() throws IOException {
        return new ByteArrayInputStream(getResponseBodyAsBytes());
    }
}
