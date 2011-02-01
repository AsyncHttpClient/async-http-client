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
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wrapper around the {@link com.ning.http.client.Response} API.
 */
public class NettyResponse implements Response {
    private final static String DEFAULT_CHARSET = "ISO-8859-1";
    private final static String HEADERS_NOT_COMPUTED = "Response's headers hasn't been computed by your AsyncHandler.";
    private final static String BODY_NOT_COMPUTED = "Response's body hasn't been computed by your AsyncHandler.";
    private final static SimpleDateFormat[] RFC2822_LIKE_DATE_FORMATS =
            {
                    new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US),
                    new SimpleDateFormat("EEE, dd-MMM-yyyy HH:mm:ss z", Locale.US),
                    new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
                    new SimpleDateFormat("EEE, dd-MMM-yyyy HH:mm:ss Z", Locale.US),
            };

    private final URI uri;
    private final Collection<HttpResponseBodyPart> bodyParts;
    private final HttpResponseHeaders headers;
    private final HttpResponseStatus status;
    private final List<Cookie> cookies = new ArrayList<Cookie>();

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

    public String getResponseBody() throws IOException {
        return getResponseBody(DEFAULT_CHARSET);
    }

    public String getResponseBody(String charset) throws IOException {
        String contentType = getContentType();
        if (contentType != null) {
            charset = AsyncHttpProviderUtils.parseCharset(contentType);
        }

        if (charset == null) {
            charset = DEFAULT_CHARSET;
        }
        
        return contentToString(charset);
    }


    String contentToString(String charset) throws UnsupportedEncodingException {
        checkBodyParts();

        StringBuilder b = new StringBuilder();
        for (HttpResponseBodyPart bp : bodyParts) {
            b.append(new String(bp.getBodyPartBytes(), charset));
        }
        return b.toString();
    }

    /* @Override */

    public InputStream getResponseBodyAsStream() throws IOException {
        checkBodyParts();

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

    private void checkBodyParts() {
        if (bodyParts == null || bodyParts.size() == 0) {
            throw new IllegalStateException(BODY_NOT_COMPUTED);
        }
    }

    /* @Override */

    public String getResponseBodyExcerpt(int maxLength) throws IOException {
        return getResponseBodyExcerpt(maxLength, DEFAULT_CHARSET);
    }

    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {
        checkBodyParts();

        String contentType = getContentType();
        if (contentType != null) {
            charset = AsyncHttpProviderUtils.parseCharset(contentType);
        }

        if (charset == null) {
            charset = DEFAULT_CHARSET;
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
        if (headers == null) {
            throw new IllegalStateException(HEADERS_NOT_COMPUTED);
        }
        return headers.getHeaders().getFirstValue("Content-Type");
    }

    /* @Override */

    public String getHeader(String name) {
        if (headers == null) {
            throw new IllegalStateException();
        }
        return headers.getHeaders().getFirstValue(name);
    }

    /* @Override */

    public List<String> getHeaders(String name) {
        if (headers == null) {
            throw new IllegalStateException(HEADERS_NOT_COMPUTED);
        }
        return headers.getHeaders().get(name);
    }

    /* @Override */

    public FluentCaseInsensitiveStringsMap getHeaders() {
        if (headers == null) {
            throw new IllegalStateException(HEADERS_NOT_COMPUTED);
        }
        return headers.getHeaders();
    }

    /* @Override */

    public boolean isRedirected() {
        return (status.getStatusCode() >= 300) && (status.getStatusCode() <= 399);
    }

    /* @Override */

    public List<Cookie> getCookies() {
        if (headers == null) {
            throw new IllegalStateException(HEADERS_NOT_COMPUTED);
        }
        if (cookies.isEmpty()) {
            for (Map.Entry<String, List<String>> header : headers.getHeaders().entrySet()) {
                if (header.getKey().equalsIgnoreCase("Set-Cookie")) {
                    // TODO: ask for parsed header
                    List<String> v = header.getValue();
                    for (String value : v) {
                        Cookie cookie = parseCookie(value);
                        cookies.add(cookie);
                    }
                }
            }
        }
        return Collections.unmodifiableList(cookies);
    }

    private Cookie parseCookie(String value) {
        String[] fields = value.split(";\\s*");
        String[] cookie = fields[0].split("=");
        String cookieName = cookie[0];
        String cookieValue = cookie[1];
        int maxAge = -1;
        String path = null;
        String domain = null;
        boolean secure = false;

        boolean maxAgeSet = false;
        boolean expiresSet = false;

        for (int j = 1; j < fields.length; j++) {
            if ("secure".equalsIgnoreCase(fields[j])) {
                secure = true;
            } else if (fields[j].indexOf('=') > 0) {
                String[] f = fields[j].split("=");

                // favor 'max-age' field over 'expires'
                if (!maxAgeSet && "max-age".equalsIgnoreCase(f[0])) {
                    try {
                        maxAge = Integer.valueOf(f[1]);
                    }
                    catch (NumberFormatException e1) {
                        // ignore failure to parse -> treat as session cookie
                        // invalidate a previously parsed expires-field
                        maxAge = -1;
                    }
                    maxAgeSet = true;
                } else if (!maxAgeSet && !expiresSet && "expires".equalsIgnoreCase(f[0])) {
                    try {
                        maxAge = convertExpireField(f[1]);
                    }
                    catch (ParseException e) {
                        // original behavior, is this correct at all (expires field with max-age semantics)? 
                        try {
                            maxAge = Integer.valueOf(f[1]);
                        }
                        catch (NumberFormatException e1) {
                            // ignore failure to parse -> treat as session cookie
                        }
                    }
                    expiresSet = true;
                } else if ("domain".equalsIgnoreCase(f[0])) {
                    domain = f[1];
                } else if ("path".equalsIgnoreCase(f[0])) {
                    path = f[1];
                }
            }
        }

        return new Cookie(domain, cookieName, cookieValue, path, maxAge, secure);
    }

    private int convertExpireField(String timestring) throws ParseException {
        ParseException exception = null;
        for (SimpleDateFormat sdf : RFC2822_LIKE_DATE_FORMATS) {
            try {
                long expire = sdf.parse(timestring).getTime();
                return (int) (expire - System.currentTimeMillis()) / 1000;
            } catch (ParseException e) {
                exception = e;
            }
        }

        throw exception;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean hasResponseStatus() {
        return (status != null ? true : false);
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean hasResponseHeaders() {
        return (headers != null ? true : false);
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean hasResponseBody() {
        return (bodyParts != null && bodyParts.size() > 0 ? true : false);
    }

}
