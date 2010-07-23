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
package com.ning.http.client.providers;

import com.ning.http.client.Cookie;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
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
public class NettyAsyncResponse implements Response {
    private final URI uri;
    private final Collection<HttpResponseBodyPart> bodyParts;
    private final HttpResponseHeaders headers;
    private final HttpResponseStatus status;
    private final List<Cookie> cookies = new ArrayList<Cookie>();

    public NettyAsyncResponse(HttpResponseStatus status,
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
    public String getResponseBody() throws IOException {
        String contentType = getContentType();
        String charset = "UTF-8";
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                if (part.startsWith("charset=")) {
                    charset = part.substring("charset=".length());
                }
            }
        }
        return contentToString(charset);
    }

    String contentToString(String charset) throws UnsupportedEncodingException {
        StringBuilder b = new StringBuilder();
        for (HttpResponseBodyPart bp : bodyParts) {
            b.append(new String(bp.getBodyPartBytes(), charset));
        }
        return b.toString();
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
        String contentType = getContentType();
        String charset = "UTF-8";
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                if (part.startsWith("charset=")) {
                    charset = part.substring("charset=".length());
                }
            }
        }
        String response = contentToString(charset);
        return response.length() <= maxLength ? response : response.substring(0, maxLength);
    }

    /* @Override */
    public URI getUri() throws MalformedURLException {
        return uri;
    }

    /* @Override */
    public String getContentType() {
        return headers.getHeaders().getFirstValue("Content-Type");
    }

    /* @Override */
    public String getHeader(String name) {
        return headers.getHeaders().getFirstValue(name);
    }

    /* @Override */
    public List<String> getHeaders(String name) {
        return headers.getHeaders().get(name);
    }

    /* @Override */
    public FluentCaseInsensitiveStringsMap getHeaders() {
        return headers.getHeaders();
    }

    /* @Override */
    public boolean isRedirected() {
        return (status.getStatusCode() >= 300) && (status.getStatusCode() <= 399);
    }
    
    /* @Override */
    public List<Cookie> getCookies() {
        if (cookies.isEmpty()) {
            for (Map.Entry<String, List<String>> header : headers.getHeaders().entrySet()) {
                if (header.getKey().equalsIgnoreCase("Set-Cookie")) {
                    // TODO: ask for parsed header
                    for (String value : header.getValue()) {
                        String[] fields = value.split(";\\s*");
                        String[] cookie = fields[0].split("=");
                        String cookieName = cookie[0];
                        String cookieValue = cookie[1];
                        String expires = "-1";
                        String path = null;
                        String domain = null;
                        boolean secure = false; // Parse each field
                        for (int j = 1; j < fields.length; j++) {
                            if ("secure".equalsIgnoreCase(fields[j])) {
                                secure = true;
                            } else if (fields[j].indexOf('=') > 0) {
                                String[] f = fields[j].split("=");
                                if ("expires".equalsIgnoreCase(f[0])) {
                                    expires = f[1];
                                } else if ("domain".equalsIgnoreCase(f[0])) {
                                    domain = f[1];
                                } else if ("path".equalsIgnoreCase(f[0])) {
                                    path = f[1];
                                }
                            }
                        }
                        cookies.add(new Cookie(domain, cookieName, cookieValue, path, Integer.valueOf(expires), secure));
                    }
                }
            }
        }
        return Collections.unmodifiableList(cookies);
    }

}
