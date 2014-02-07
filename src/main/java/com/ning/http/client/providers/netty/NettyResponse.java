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

import static com.ning.http.util.MiscUtil.isNonEmpty;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBuffers;

import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import com.ning.http.client.cookie.Cookie;
import com.ning.http.client.cookie.CookieDecoder;
import com.ning.http.util.AsyncHttpProviderUtils;

/**
 * Wrapper around the {@link com.ning.http.client.Response} API.
 */
public class NettyResponse implements Response {
    private final static Charset DEFAULT_CHARSET = Charset.forName("ISO-8859-1");

    private final List<HttpResponseBodyPart> bodyParts;
    private final HttpResponseHeaders headers;
    private final HttpResponseStatus status;
    private List<Cookie> cookies;

    public NettyResponse(HttpResponseStatus status,
                         HttpResponseHeaders headers,
                         List<HttpResponseBodyPart> bodyParts) {

        this.status = status;
        this.headers = headers;
        this.bodyParts = bodyParts;
    }

    /* @Override */

    public int getStatusCode() {
        return status.getStatusCode();
    }

    /* @Override */

    public String getStatusText() {
        return status.getStatusText();
    }

    /* @Override */
    public byte[] getResponseBodyAsBytes() throws IOException {
        return ChannelBufferUtil.channelBuffer2bytes(getResponseBodyAsChannelBuffer());
    }

    public ByteBuffer getResponseBodyAsByteBuffer() throws IOException {
        return getResponseBodyAsChannelBuffer().toByteBuffer();
    }

    /* @Override */
    public String getResponseBody() throws IOException {
        return getResponseBody(null);
    }

    public String getResponseBody(String charset) throws IOException {
        return getResponseBodyAsChannelBuffer().toString(computeCharset(charset));
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

    /* @Override */

    public String getResponseBodyExcerpt(int maxLength) throws IOException {
        return getResponseBodyExcerpt(maxLength, null);
    }

    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {
        String response = getResponseBody(charset);
        return response.length() <= maxLength ? response : response.substring(0, maxLength);
    }

    private Charset computeCharset(String charset) {
        if (charset == null) {
            String contentType = getContentType();
            if (contentType != null)
                charset = AsyncHttpProviderUtils.parseCharset(contentType); // parseCharset can return null
        }
        return charset != null ? Charset.forName(charset) : DEFAULT_CHARSET;
    }

    /* @Override */

    public URI getUri() throws MalformedURLException {
        return status.getUrl();
    }

    /* @Override */

    public String getContentType() {
        return getHeader("Content-Type");
    }

    /* @Override */

    public String getHeader(String name) {
        return headers != null ? headers.getHeaders().getFirstValue(name) : null;
    }

    /* @Override */

    public List<String> getHeaders(String name) {
        return headers != null ? headers.getHeaders().get(name) : Collections.<String> emptyList();
    }

    /* @Override */

    public FluentCaseInsensitiveStringsMap getHeaders() {
        return headers != null ? headers.getHeaders() : new FluentCaseInsensitiveStringsMap();
    }

    /* @Override */

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

    /* @Override */

    public List<Cookie> getCookies() {
        if (headers == null) {
            return Collections.emptyList();
        }
        if (cookies == null) {
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
            cookies = Collections.unmodifiableList(localCookies);
        }
        return cookies;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean hasResponseStatus() {
        return status != null;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean hasResponseHeaders() {
        return headers != null;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean hasResponseBody() {
        return isNonEmpty(bodyParts);
    }

}
