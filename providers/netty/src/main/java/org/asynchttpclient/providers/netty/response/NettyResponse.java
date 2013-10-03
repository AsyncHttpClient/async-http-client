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
 */
package org.asynchttpclient.providers.netty.response;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.asynchttpclient.Cookie;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.org.jboss.netty.handler.codec.http.CookieDecoder;
import org.asynchttpclient.providers.ResponseBase;
import org.asynchttpclient.util.AsyncHttpProviderUtils;

/**
 * Wrapper around the {@link org.asynchttpclient.Response} API.
 */
public class NettyResponse extends ResponseBase {

    public NettyResponse(HttpResponseStatus status,
                         HttpResponseHeaders headers,
                         List<HttpResponseBodyPart> bodyParts) {
        super(status, headers, bodyParts);
    }

    @Override
    public String getResponseBodyExcerpt(int maxLength) throws IOException {
        return getResponseBodyExcerpt(maxLength, null);
    }

    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {
        // should be fine; except that it may split multi-byte chars (last char may become '?')
        charset = calculateCharset(charset);
        byte[] b = AsyncHttpProviderUtils.contentToBytes(bodyParts, maxLength);
        return new String(b, charset);
    }
    
    protected List<Cookie> buildCookies() {
    	List<Cookie> cookies = new ArrayList<Cookie>();
        for (Map.Entry<String, List<String>> header : headers.getHeaders().entrySet()) {
            if (header.getKey().equalsIgnoreCase("Set-Cookie")) {
                // TODO: ask for parsed header
                List<String> v = header.getValue();
                for (String value : v) {
                    cookies.addAll(CookieDecoder.decode(value));
                }
            }
        }
        return Collections.unmodifiableList(cookies);
    }

    @Override
    public byte[] getResponseBodyAsBytes() throws IOException {
        return getResponseBodyAsByteBuffer().array();
    }

    @Override
    public ByteBuffer getResponseBodyAsByteBuffer() throws IOException {

        int length = 0;
        for (HttpResponseBodyPart part: bodyParts)
            length += part.length();

        ByteBuffer target = ByteBuffer.wrap(new byte[length]);
        for (HttpResponseBodyPart part: bodyParts)
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
