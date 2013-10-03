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

import static com.ning.http.util.MiscUtil.isNonEmpty;

import com.ning.org.jboss.netty.handler.codec.http.CookieDecoder;
import com.ning.http.client.Cookie;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import com.ning.http.util.AsyncHttpProviderUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;


public class JDKResponse implements Response {
    private final static String DEFAULT_CHARSET = "ISO-8859-1";

    private final URI uri;
    private final List<HttpResponseBodyPart> bodyParts;
    private final HttpResponseHeaders headers;
    private final HttpResponseStatus status;
    private List<Cookie> cookies;
    private AtomicBoolean contentComputed = new AtomicBoolean(false);
    private String content;

    public JDKResponse(HttpResponseStatus status,
                       HttpResponseHeaders headers,
                       List<HttpResponseBodyPart> bodyParts) {

        this.bodyParts = bodyParts;
        this.headers = headers;
        this.status = status;

        uri = this.status.getUrl();
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
        return getResponseBody(DEFAULT_CHARSET);
    }

    /* @Override */
    public byte[] getResponseBodyAsBytes() throws IOException {
        return AsyncHttpProviderUtils.contentToByte(bodyParts);
    }

    public ByteBuffer getResponseBodyAsByteBuffer() throws IOException {
        return ByteBuffer.wrap(getResponseBodyAsBytes());
    }

    public String getResponseBody(String charset) throws IOException {

    	if (!contentComputed.get()) {
            content = AsyncHttpProviderUtils.contentToString(bodyParts, computeCharset(charset));
        }
        return content;
    }
    
    /* @Override */
    public InputStream getResponseBodyAsStream() throws IOException {
        if (contentComputed.get()) {
            return new ByteArrayInputStream(content.getBytes(DEFAULT_CHARSET));
        }

        return AsyncHttpProviderUtils.contentToInputStream(bodyParts);
    }

    /* @Override */

    public String getResponseBodyExcerpt(int maxLength) throws IOException {
        return getResponseBodyExcerpt(maxLength, DEFAULT_CHARSET);
    }

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
        return charset != null? charset: DEFAULT_CHARSET;
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
        if (!isNonEmpty(cookies)) {
        	List<Cookie> localCookies = new ArrayList<Cookie>();
            for (Map.Entry<String, List<String>> header : headers.getHeaders().entrySet()) {
                if (header.getKey().equalsIgnoreCase("Set-Cookie")) {
                    // TODO: ask for parsed header
                    List<String> v = header.getValue();
                    for (String value : v) {
                        Set<Cookie> cookies = CookieDecoder.decode(value);
                        localCookies.addAll(cookies);
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
        return bodyParts != null;
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
