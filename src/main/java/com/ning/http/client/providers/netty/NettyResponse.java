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

import com.ning.http.client.Cookie;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import com.ning.http.util.AsyncHttpProviderUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBuffers;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Wrapper around the {@link com.ning.http.client.Response} API.
 */
public class NettyResponse implements Response {
    private final static String DEFAULT_CHARSET = "ISO-8859-1";

    private final URI uri;
    private final Collection<HttpResponseBodyPart> bodyParts;
    private final HttpResponseHeaders headers;
    private final HttpResponseStatus status;
    private List<Cookie> cookies;

    public NettyResponse(HttpResponseStatus status,
                         HttpResponseHeaders headers,
                         Collection<HttpResponseBodyPart> bodyParts) {

        this.status = status;
        this.headers = headers;
        this.bodyParts = bodyParts;
        uri = status.getUrl();
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
        return AsyncHttpProviderUtils.contentToByte(bodyParts);
    }

    /* @Override */
    public String getResponseBody() throws IOException {
        return getResponseBody(null);
    }

    public String getResponseBody(String charset) throws IOException {
        return AsyncHttpProviderUtils.contentToString(bodyParts, computeCharset(charset));
    }

    /* @Override */
    public InputStream getResponseBodyAsStream() throws IOException {
        ChannelBuffer buf = ChannelBuffers.dynamicBuffer();
        for (HttpResponseBodyPart bp : bodyParts) {
            // Ugly. TODO
            // (1) We must remove the downcast,
            // (2) we need a CompositeByteArrayInputStream to avoid
            // copying the bytes.
            if (bp.getClass().isAssignableFrom(ResponseBodyPart.class)) {
                buf.writeBytes(bp.getBodyPartBytes());
            }
        }
        return new ChannelBufferInputStream(buf);
    }

    /* @Override */

    public String getResponseBodyExcerpt(int maxLength) throws IOException {
        return getResponseBodyExcerpt(maxLength, null);
    }

    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {
        String response = AsyncHttpProviderUtils.contentToString(bodyParts, computeCharset(charset));
        return response.length() <= maxLength ? response : response.substring(0, maxLength);
    }
    
    private String computeCharset(String charset) {
    	String contentType = getContentType();
        if (charset == null) {
        	if (contentType != null)
        		charset = AsyncHttpProviderUtils.parseCharset(contentType);
        	else
        		charset = DEFAULT_CHARSET;
        }
        return charset;
    }

    /* @Override */

    public URI getUri() throws MalformedURLException {
        return uri;
    }

    /* @Override */

    public String getContentType() {
        return getHeader("Content-Type");
    }

    /* @Override */

    public String getHeader(String name) {
        return headers != null? headers.getHeaders().getFirstValue(name): null;
    }

    /* @Override */

    public List<String> getHeaders(String name) {
        return headers != null? headers.getHeaders().get(name): Collections.<String> emptyList();
    }

    /* @Override */

    public FluentCaseInsensitiveStringsMap getHeaders() {
        return headers != null? headers.getHeaders(): new FluentCaseInsensitiveStringsMap();
    }

    /* @Override */

    public boolean isRedirected() {
        return (status.getStatusCode() >= 300) && (status.getStatusCode() <= 399);
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
                        Cookie cookie = AsyncHttpProviderUtils.parseCookie(value);
                        localCookies.add(cookie);
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
        return bodyParts != null && !bodyParts.isEmpty();
    }

}
