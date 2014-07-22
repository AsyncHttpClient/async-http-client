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
package com.ning.http.client.providers.apache;

import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.ResponseBase;
import com.ning.http.client.cookie.Cookie;
import com.ning.http.client.cookie.CookieDecoder;
import com.ning.http.util.AsyncHttpProviderUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ApacheResponse extends ResponseBase {

    public ApacheResponse(HttpResponseStatus status, HttpResponseHeaders headers, List<HttpResponseBodyPart> bodyParts) {
        super(status, headers, bodyParts);
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
    public String getResponseBody() throws IOException {
        return getResponseBody(DEFAULT_CHARSET);
    }

    @Override
    public String getResponseBody(String charset) throws IOException {
        return AsyncHttpProviderUtils.contentToString(bodyParts, computeCharset(charset));
    }

    @Override
    public InputStream getResponseBodyAsStream() throws IOException {
        return AsyncHttpProviderUtils.contentToInputStream(bodyParts);
    }

    @Override
    public String getResponseBodyExcerpt(int maxLength) throws IOException {
        return getResponseBodyExcerpt(maxLength, DEFAULT_CHARSET);
    }

    @Override
    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {
        charset = computeCharset(charset);

        String response = AsyncHttpProviderUtils.contentToString(bodyParts, charset);
        return response.length() <= maxLength ? response : response.substring(0, maxLength);
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
                    Cookie cookie = CookieDecoder.decode(value);
                    localCookies.add(cookie);
                }
            }
        }
        return localCookies;
    }
}
