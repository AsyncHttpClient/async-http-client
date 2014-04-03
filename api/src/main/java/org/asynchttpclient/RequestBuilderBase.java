/*
 * Copyright 2010-2013 Ning, Inc.
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
package org.asynchttpclient;

import static org.asynchttpclient.util.MiscUtil.isNonEmpty;

import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.multipart.Part;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.asynchttpclient.util.StandardCharsets;
import org.asynchttpclient.util.UTF8UrlEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Builder for {@link Request}
 * 
 * @param <T>
 */
public abstract class RequestBuilderBase<T extends RequestBuilderBase<T>> {
    private final static Logger logger = LoggerFactory.getLogger(RequestBuilderBase.class);

    private static final URI DEFAULT_REQUEST_URL = URI.create("http://localhost");

    private static final class RequestImpl implements Request {
        private String method;
        private URI originalUri;
        private URI uri;
        private URI rawUri;
        private InetAddress address;
        private InetAddress localAddress;
        private FluentCaseInsensitiveStringsMap headers;
        private Collection<Cookie> cookies;
        private byte[] byteData;
        private String stringData;
        private InputStream streamData;
        private BodyGenerator bodyGenerator;
        private FluentStringsMap params;
        private List<Part> parts;
        private String virtualHost;
        private long length = -1;
        public FluentStringsMap queryParams;
        public ProxyServer proxyServer;
        private Realm realm;
        private File file;
        private Boolean followRedirects;
        private int requestTimeoutInMs;
        private long rangeOffset;
        public String charset;
        private boolean useRawUrl;
        private ConnectionPoolKeyStrategy connectionPoolKeyStrategy = DefaultConnectionPoolStrategy.INSTANCE;

        public RequestImpl(boolean useRawUrl) {
            this.useRawUrl = useRawUrl;
        }

        public RequestImpl(Request prototype) {
            if (prototype != null) {
                this.method = prototype.getMethod();
                this.originalUri = prototype.getOriginalURI();
                this.address = prototype.getInetAddress();
                this.localAddress = prototype.getLocalAddress();
                this.headers = new FluentCaseInsensitiveStringsMap(prototype.getHeaders());
                this.cookies = new ArrayList<Cookie>(prototype.getCookies());
                this.byteData = prototype.getByteData();
                this.stringData = prototype.getStringData();
                this.streamData = prototype.getStreamData();
                this.bodyGenerator = prototype.getBodyGenerator();
                this.params = (prototype.getParams() == null ? null : new FluentStringsMap(prototype.getParams()));
                this.queryParams = (prototype.getQueryParams() == null ? null : new FluentStringsMap(prototype.getQueryParams()));
                this.parts = (prototype.getParts() == null ? null : new ArrayList<Part>(prototype.getParts()));
                this.virtualHost = prototype.getVirtualHost();
                this.length = prototype.getContentLength();
                this.proxyServer = prototype.getProxyServer();
                this.realm = prototype.getRealm();
                this.file = prototype.getFile();
                this.followRedirects = prototype.isRedirectOverrideSet() ? prototype.isRedirectEnabled() : null;
                this.requestTimeoutInMs = prototype.getRequestTimeoutInMs();
                this.rangeOffset = prototype.getRangeOffset();
                this.charset = prototype.getBodyEncoding();
                this.useRawUrl = prototype.isUseRawUrl();
                this.connectionPoolKeyStrategy = prototype.getConnectionPoolKeyStrategy();
            }
        }

        @Override
        public String getMethod() {
            return method;
        }

        @Override
        public InetAddress getInetAddress() {
            return address;
        }

        @Override
        public InetAddress getLocalAddress() {
            return localAddress;
        }

        private String removeTrailingSlash(URI uri) {
            String uriString = uri.toString();
            if (uriString.endsWith("/")) {
                return uriString.substring(0, uriString.length() - 1);
            } else {
                return uriString;
            }
        }

        @Override
        public String getUrl() {
            return removeTrailingSlash(getURI());
        }

        @Override
        public String getRawUrl() {
            return removeTrailingSlash(getRawURI());
        }

        public URI getOriginalURI() {
            return originalUri;
        }

        public URI getURI() {
            if (uri == null)
                uri = toURI(true);
            return uri;
        }

        public URI getRawURI() {
            if (rawUri == null)
                rawUri = toURI(false);
            return rawUri;
        }

        private URI toURI(boolean encode) {

            if (originalUri == null) {
                logger.debug("setUrl hasn't been invoked. Using http://localhost");
                originalUri = DEFAULT_REQUEST_URL;
            }

            AsyncHttpProviderUtils.validateSupportedScheme(originalUri);

            StringBuilder builder = new StringBuilder();
            builder.append(originalUri.getScheme()).append("://").append(originalUri.getRawAuthority());
            if (isNonEmpty(originalUri.getRawPath())) {
                builder.append(originalUri.getRawPath());
            } else {
                builder.append("/");
            }

            if (isNonEmpty(queryParams)) {

                builder.append("?");

                for (Iterator<Entry<String, List<String>>> i = queryParams.iterator(); i.hasNext();) {
                    Map.Entry<String, List<String>> param = i.next();
                    String name = param.getKey();
                    for (Iterator<String> j = param.getValue().iterator(); j.hasNext();) {
                        String value = j.next();
                        if (encode) {
                            UTF8UrlEncoder.appendEncoded(builder, name);
                        } else {
                            builder.append(name);
                        }
                        if (value != null) {
                            builder.append('=');
                            if (encode) {
                                UTF8UrlEncoder.appendEncoded(builder, value);
                            } else {
                                builder.append(value);
                            }
                        }
                        if (j.hasNext()) {
                            builder.append('&');
                        }
                    }
                    if (i.hasNext()) {
                        builder.append('&');
                    }
                }
            }

            return URI.create(builder.toString());
        }

        @Override
        public FluentCaseInsensitiveStringsMap getHeaders() {
            if (headers == null) {
                headers = new FluentCaseInsensitiveStringsMap();
            }
            return headers;
        }

        @Override
        public boolean hasHeaders() {
            return headers != null && !headers.isEmpty();
        }

        @Override
        public Collection<Cookie> getCookies() {
            if (cookies == null) {
                cookies = Collections.unmodifiableCollection(Collections.<Cookie> emptyList());
            }
            return cookies;
        }

        @Override
        public byte[] getByteData() {
            return byteData;
        }

        @Override
        public String getStringData() {
            return stringData;
        }

        @Override
        public InputStream getStreamData() {
            return streamData;
        }

        @Override
        public BodyGenerator getBodyGenerator() {
            return bodyGenerator;
        }

        @Override
        public long getContentLength() {
            return length;
        }

        @Override
        public FluentStringsMap getParams() {
            return params;
        }

        @Override
        public List<Part> getParts() {
            return parts;
        }

        @Override
        public String getVirtualHost() {
            return virtualHost;
        }

        @Override
        public FluentStringsMap getQueryParams() {
            return queryParams;
        }

        @Override
        public ProxyServer getProxyServer() {
            return proxyServer;
        }

        @Override
        public Realm getRealm() {
            return realm;
        }

        @Override
        public File getFile() {
            return file;
        }

        @Override
        public boolean isRedirectEnabled() {
            return followRedirects != null && followRedirects;
        }

        @Override
        public boolean isRedirectOverrideSet() {
            return followRedirects != null;
        }

        @Override
        public int getRequestTimeoutInMs() {
            return requestTimeoutInMs;
        }

        public long getRangeOffset() {
            return rangeOffset;
        }

        public String getBodyEncoding() {
            return charset;
        }

        public ConnectionPoolKeyStrategy getConnectionPoolKeyStrategy() {
            return connectionPoolKeyStrategy;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(getURI().toString());

            sb.append("\t");
            sb.append(method);
            sb.append("\theaders:");
            final FluentCaseInsensitiveStringsMap headersLocal = getHeaders();
            if (headersLocal != null) {
                for (String name : headersLocal.keySet()) {
                    sb.append("\t");
                    sb.append(name);
                    sb.append(":");
                    sb.append(headersLocal.getJoinedValue(name, ", "));
                }
            }
            sb.append("\tparams:");
            if (params != null) {
                for (String name : params.keySet()) {
                    sb.append("\t");
                    sb.append(name);
                    sb.append(":");
                    sb.append(params.getJoinedValue(name, ", "));
                }
            }

            return sb.toString();
        }

        public boolean isUseRawUrl() {
            return useRawUrl;
        }
    }

    private final Class<T> derived;
    protected final RequestImpl request;
    protected boolean useRawUrl = false;

    protected RequestBuilderBase(Class<T> derived, String method, boolean rawUrls) {
        this.derived = derived;
        request = new RequestImpl(rawUrls);
        request.method = method;
        this.useRawUrl = rawUrls;
    }

    protected RequestBuilderBase(Class<T> derived, Request prototype) {
        this.derived = derived;
        request = new RequestImpl(prototype);
        this.useRawUrl = prototype.isUseRawUrl();
    }

    public T setUrl(String url) {
        return setURI(URI.create(url));
    }

    public T setURI(URI uri) {
        if (uri.getPath() == null)
            throw new IllegalArgumentException("Unsupported uri format: " + uri);
        request.originalUri = uri;
        addQueryParameters(request.originalUri);
        request.uri = null;
        request.rawUri = null;
        return derived.cast(this);
    }

    public T setInetAddress(InetAddress address) {
        request.address = address;
        return derived.cast(this);
    }

    public T setLocalInetAddress(InetAddress address) {
        request.localAddress = address;
        return derived.cast(this);
    }

    private void addQueryParameters(URI uri) {
        if (isNonEmpty(uri.getRawQuery())) {
            String[] queries = uri.getRawQuery().split("&");
            int pos;
            for (String query : queries) {
                pos = query.indexOf('=');
                if (pos <= 0) {
                    addQueryParameter(query, null);
                } else {
                    try {
                        if (useRawUrl) {
                            addQueryParameter(query.substring(0, pos), query.substring(pos + 1));
                        } else {
                            addQueryParameter(URLDecoder.decode(query.substring(0, pos), StandardCharsets.UTF_8.name()),
                                    URLDecoder.decode(query.substring(pos + 1), StandardCharsets.UTF_8.name()));
                        }
                    } catch (UnsupportedEncodingException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    public T setVirtualHost(String virtualHost) {
        request.virtualHost = virtualHost;
        return derived.cast(this);
    }

    public T setHeader(String name, String value) {
        request.getHeaders().replace(name, value);
        return derived.cast(this);
    }

    public T addHeader(String name, String value) {
        if (value == null) {
            logger.warn("Value was null, set to \"\"");
            value = "";
        }

        request.getHeaders().add(name, value);
        return derived.cast(this);
    }

    public T setHeaders(FluentCaseInsensitiveStringsMap headers) {
        if (headers != null) {
            request.headers = new FluentCaseInsensitiveStringsMap(headers);
        }
        return derived.cast(this);
    }

    public T setHeaders(Map<String, Collection<String>> headers) {
        if (headers != null) {
            request.headers = new FluentCaseInsensitiveStringsMap(headers);
        }
        return derived.cast(this);
    }

    public T setContentLength(int length) {
        request.length = length;
        return derived.cast(this);
    }

    public T addCookie(Cookie cookie) {
        if (request.cookies == null) {
            request.cookies = new ArrayList<Cookie>();
        }
        request.cookies.add(cookie);
        return derived.cast(this);
    }

    public void resetQueryParameters() {
        request.queryParams = null;
    }

    public void resetCookies() {
        request.cookies.clear();
    }

    public void resetParameters() {
        request.params = null;
    }

    public void resetNonMultipartData() {
        request.byteData = null;
        request.stringData = null;
        request.streamData = null;
        request.length = -1;
    }

    public void resetMultipartData() {
        request.parts = null;
    }

    public T setBody(File file) {
        request.file = file;
        return derived.cast(this);
    }

    public T setBody(byte[] data) throws IllegalArgumentException {
        resetParameters();
        resetNonMultipartData();
        resetMultipartData();
        request.byteData = data;
        return derived.cast(this);
    }

    public T setBody(String data) throws IllegalArgumentException {
        resetParameters();
        resetNonMultipartData();
        resetMultipartData();
        request.stringData = data;
        return derived.cast(this);
    }

    public T setBody(InputStream stream) throws IllegalArgumentException {
        resetParameters();
        resetNonMultipartData();
        resetMultipartData();
        request.streamData = stream;
        return derived.cast(this);
    }

    public T setBody(BodyGenerator bodyGenerator) {
        request.bodyGenerator = bodyGenerator;
        return derived.cast(this);
    }

    public T addQueryParameter(String name, String value) {
        if (request.queryParams == null) {
            request.queryParams = new FluentStringsMap();
        }
        request.queryParams.add(name, value);
        return derived.cast(this);
    }

    public T setQueryParameters(FluentStringsMap parameters) {
        if (parameters == null) {
            request.queryParams = null;
        } else {
            request.queryParams = new FluentStringsMap(parameters);
        }
        return derived.cast(this);
    }

    public T addParameter(String key, String value) throws IllegalArgumentException {
        resetNonMultipartData();
        resetMultipartData();
        if (request.params == null) {
            request.params = new FluentStringsMap();
        }
        request.params.add(key, value);
        return derived.cast(this);
    }

    public T setParameters(FluentStringsMap parameters) throws IllegalArgumentException {
        resetNonMultipartData();
        resetMultipartData();
        request.params = new FluentStringsMap(parameters);
        return derived.cast(this);
    }

    public T setParameters(Map<String, Collection<String>> parameters) {
        resetNonMultipartData();
        resetMultipartData();
        request.params = new FluentStringsMap(parameters);
        return derived.cast(this);
    }

    public T addBodyPart(Part part) {
        resetParameters();
        resetNonMultipartData();
        if (request.parts == null) {
            request.parts = new ArrayList<Part>();
        }
        request.parts.add(part);
        return derived.cast(this);
    }

    public T setProxyServer(ProxyServer proxyServer) {
        request.proxyServer = proxyServer;
        return derived.cast(this);
    }

    public T setRealm(Realm realm) {
        request.realm = realm;
        return derived.cast(this);
    }

    public T setFollowRedirects(boolean followRedirects) {
        request.followRedirects = followRedirects;
        return derived.cast(this);
    }

    public T setRequestTimeoutInMs(int requestTimeoutInMs) {
        request.requestTimeoutInMs = requestTimeoutInMs;
        return derived.cast(this);
    }

    public T setRangeOffset(long rangeOffset) {
        request.rangeOffset = rangeOffset;
        return derived.cast(this);
    }

    public T setMethod(String method) {
        request.method = method;
        return derived.cast(this);
    }

    public T setBodyEncoding(String charset) {
        request.charset = charset;
        return derived.cast(this);
    }

    public T setConnectionPoolKeyStrategy(ConnectionPoolKeyStrategy connectionPoolKeyStrategy) {
        request.connectionPoolKeyStrategy = connectionPoolKeyStrategy;
        return derived.cast(this);
    }

    public Request build() {
        if (request.length < 0 && request.streamData == null) {
            // can't concatenate content-length
            String contentLength = null;
            if (request.headers != null && request.headers.isEmpty()) {
                contentLength = request.headers.getFirstValue("Content-Length");
            }

            if (contentLength != null) {
                try {
                    request.length = Long.parseLong(contentLength);
                } catch (NumberFormatException e) {
                    // NoOp -- we won't specify length so it will be chunked?
                }
            }
        }
        if (request.cookies != null) {
            request.cookies = Collections.unmodifiableCollection(request.cookies);
        }
        return request;
    }

    public T addOrReplaceCookie(Cookie cookie) {
        String cookieKey = cookie.getName();
        boolean replace = false;
        int index = 0;
        if (request.cookies == null) {
            request.cookies = new ArrayList<Cookie>();
            request.cookies.add(cookie);
            return derived.cast(this);
        }
        for (Cookie c : request.cookies) {
            if (c.getName().equals(cookieKey)) {
                replace = true;
                break;
            }

            index++;
        }
        if (replace) {
            ((ArrayList<Cookie>) request.cookies).set(index, cookie);
        } else {
            request.cookies.add(cookie);
        }
        return derived.cast(this);
    }
}
