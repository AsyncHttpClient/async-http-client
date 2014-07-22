/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client.providers.jdk;

import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.ResponseBase;
import com.ning.http.client.cookie.Cookie;
import com.ning.http.client.cookie.CookieDecoder;
import com.ning.http.util.AsyncHttpProviderUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class JDKResponse extends ResponseBase {

    private AtomicBoolean contentComputed = new AtomicBoolean(false);
    private String content;

    public JDKResponse(HttpResponseStatus status, HttpResponseHeaders headers, List<HttpResponseBodyPart> bodyParts) {
        super(status, headers, bodyParts);
    }

    @Override
    public String getResponseBody() throws IOException {
        return getResponseBody(DEFAULT_CHARSET);
    }

    @Override
    public byte[] getResponseBodyAsBytes() throws IOException {
        return AsyncHttpProviderUtils.contentToByte(bodyParts);
    }

    @Override
    public ByteBuffer getResponseBodyAsByteBuffer() throws IOException {
        return ByteBuffer.wrap(getResponseBodyAsBytes());
    }

    @Override
    public String getResponseBody(String charset) throws IOException {

        if (!contentComputed.get()) {
            content = AsyncHttpProviderUtils.contentToString(bodyParts, computeCharset(charset));
        }
        return content;
    }

    @Override
    public InputStream getResponseBodyAsStream() throws IOException {
        if (contentComputed.get()) {
            return new ByteArrayInputStream(content.getBytes(DEFAULT_CHARSET));
        }

        return AsyncHttpProviderUtils.contentToInputStream(bodyParts);
    }

    @Override
    public String getResponseBodyExcerpt(int maxLength) throws IOException {
        return getResponseBodyExcerpt(maxLength, DEFAULT_CHARSET);
    }

    @Override
    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {
        charset = computeCharset(charset);

        if (!contentComputed.get()) {
            content = AsyncHttpProviderUtils.contentToString(bodyParts, charset == null ? DEFAULT_CHARSET : charset);
        }

        return content.length() <= maxLength ? content : content.substring(0, maxLength);
    }

    private String computeCharset(String charset) {
        if (charset == null) {
            String contentType = getContentType();
            if (contentType != null)
                charset = AsyncHttpProviderUtils.parseCharset(contentType); // parseCharset can return null
        }
        return charset != null ? charset : DEFAULT_CHARSET;
    }

    @Override
    protected List<Cookie> buildCookies() {
        List<Cookie> localCookies = new ArrayList<Cookie>();
        for (Map.Entry<String, List<String>> header : headers.getHeaders().entrySet()) {
            if (header.getKey().equalsIgnoreCase("Set-Cookie")) {
                // TODO: ask for parsed header
                List<String> v = header.getValue();
                for (String value : v) {
                    localCookies.add(CookieDecoder.decode(value));
                }
            }
        }
        return localCookies;
    }
}
