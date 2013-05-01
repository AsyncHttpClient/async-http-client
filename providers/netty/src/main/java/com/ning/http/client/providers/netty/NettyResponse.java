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
package com.ning.http.client.providers.netty;

import com.ning.org.jboss.netty.handler.codec.http.CookieDecoder;
import com.ning.http.client.Cookie;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.providers.ResponseBase;
import com.ning.http.util.AsyncHttpProviderUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBuffers;

/**
 * Wrapper around the {@link com.ning.http.client.Response} API.
 */
public class NettyResponse extends ResponseBase {

    public NettyResponse(HttpResponseStatus status,
                         HttpResponseHeaders headers,
                         List<HttpResponseBodyPart> bodyParts) {
        super(status, headers, bodyParts);
    }

    /* @Override */
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

    /* @Override */
    public byte[] getResponseBodyAsBytes() throws IOException {
        return getResponseBodyAsByteBuffer().array();
    }

    /* @Override */
    public ByteBuffer getResponseBodyAsByteBuffer() throws IOException {
        return getResponseBodyAsChannelBuffer().toByteBuffer();
    }

    /* @Override */
    public String getResponseBody() throws IOException {
        return getResponseBody(null);
    }

    /* @Override */
    public String getResponseBody(String charset) throws IOException {
        return getResponseBodyAsChannelBuffer().toString(Charset.forName(calculateCharset(charset)));
    }

    /* @Override */
    public InputStream getResponseBodyAsStream() throws IOException {
        return new ChannelBufferInputStream(getResponseBodyAsChannelBuffer());
    }

    public ChannelBuffer getResponseBodyAsChannelBuffer() throws IOException {
        ChannelBuffer b = null;
        switch (bodyParts.size()) {
        case 0:
            b = ChannelBuffers.EMPTY_BUFFER;
            break;
        case 1:
            b = ResponseBodyPart.class.cast(bodyParts.get(0)).getChannelBuffer();
            break;
        default:
            ChannelBuffer[] channelBuffers = new ChannelBuffer[bodyParts.size()];
            for (int i = 0; i < bodyParts.size(); i++) {
                channelBuffers[i] = ResponseBodyPart.class.cast(bodyParts.get(i)).getChannelBuffer();
            }
            b = ChannelBuffers.wrappedBuffer(channelBuffers);
        }

        return b;
    }
}
