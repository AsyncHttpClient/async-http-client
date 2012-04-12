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
package com.ning.http.client;

import com.ning.http.client.Request.EntityWriter;
import com.ning.http.util.UTF8UrlEncoder;
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

    private static final class RequestImpl implements Request {
        private String method;
        private String url = null;
        private InetAddress address = null;
        private InetAddress localAddress = null;
        private FluentCaseInsensitiveStringsMap headers = new FluentCaseInsensitiveStringsMap();
        private Collection<Cookie> cookies = new ArrayList<Cookie>();
        private byte[] byteData;
        private String stringData;
        private InputStream streamData;
        private EntityWriter entityWriter;
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
        private PerRequestConfig perRequestConfig;
        private long rangeOffset = 0;
        public String charset;
        private boolean useRawUrl = false;

        public RequestImpl(boolean useRawUrl) {
            this.useRawUrl = useRawUrl;
        }

        public RequestImpl(Request prototype) {
            if (prototype != null) {
                this.method = prototype.getMethod();
                int pos = prototype.getUrl().indexOf("?");
                this.url = pos > 0 ? prototype.getUrl().substring(0, pos) : prototype.getUrl();
                this.address = prototype.getInetAddress();
                this.localAddress = prototype.getLocalAddress();
                this.headers = new FluentCaseInsensitiveStringsMap(prototype.getHeaders());
                this.cookies = new ArrayList<Cookie>(prototype.getCookies());
                this.byteData = prototype.getByteData();
                this.stringData = prototype.getStringData();
                this.streamData = prototype.getStreamData();
                this.entityWriter = prototype.getEntityWriter();
                this.bodyGenerator = prototype.getBodyGenerator();
                this.params = (prototype.getParams() == null ? null : new FluentStringsMap(prototype.getParams()));
                this.queryParams = (prototype.getQueryParams() == null ? null : new FluentStringsMap(prototype.getQueryParams()));
                this.parts = (prototype.getParts() == null ? null : new ArrayList<Part>(prototype.getParts()));
                this.virtualHost = prototype.getVirtualHost();
                this.length = prototype.getContentLength();
                this.proxyServer = prototype.getProxyServer();
                this.realm = prototype.getRealm();
                this.file = prototype.getFile();
                this.followRedirects = prototype.isRedirectOverrideSet()? prototype.isRedirectEnabled() : null;
                this.perRequestConfig = prototype.getPerRequestConfig();
                this.rangeOffset = prototype.getRangeOffset();
                this.charset = prototype.getBodyEncoding();
                this.useRawUrl = prototype.isUseRawUrl();
            }
        }

        /* @Override */

        public String getReqType() {
            return getMethod();
        }

        public String getMethod() {
            return method;
        }

        /* @Override */

        public String getUrl() {
            return toUrl(true);
        }

        public InetAddress getInetAddress() {
            return address;
        }

        public InetAddress getLocalAddress() {
            return localAddress;
        }
        
        private String toUrl(boolean encode) {

            if (url == null) {
                logger.debug("setUrl hasn't been invoked. Using http://localhost");
                url = "http://localhost";
            }

            String uri = url;
            if (!uri.startsWith("ws")) {
                try {
                    uri = URI.create(url).toURL().toString();
                } catch (Throwable e) {
                    throw new IllegalArgumentException("Illegal URL: " + url, e);
                }
            }

            if (queryParams != null && !queryParams.isEmpty()) {

                StringBuilder builder = new StringBuilder();
                if (!url.substring(8).contains("/")) { // no other "/" than http[s]:// -> http://localhost:1234
                    builder.append("/");
                }
                builder.append("?");

                for (Iterator<Entry<String, List<String>>> i = queryParams.iterator(); i.hasNext(); ) {
                    Map.Entry<String, List<String>> param = i.next();
                    String name = param.getKey();
                    for (Iterator<String> j = param.getValue().iterator(); j.hasNext(); ) {
                        String value = j.next();
                        if (encode) {
                            UTF8UrlEncoder.appendEncoded(builder, name);
                        } else {
                            builder.append(name);
                        }
                        if (value != null && !value.equals("")) {
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
                uri += builder.toString();
            }
            return uri;
        }

        /* @Override */
        public String getRawUrl() {
            return toUrl(false);
        }

        /* @Override */
        public FluentCaseInsensitiveStringsMap getHeaders() {
            return headers;
        }

        /* @Override */
        public Collection<Cookie> getCookies() {
            return Collections.unmodifiableCollection(cookies);
        }

        /* @Override */
        public byte[] getByteData() {
            return byteData;
        }

        /* @Override */
        public String getStringData() {
            return stringData;
        }

        /* @Override */
        public InputStream getStreamData() {
            return streamData;
        }

        /* @Override */
        public EntityWriter getEntityWriter() {
            return entityWriter;
        }

        /* @Override */
        public BodyGenerator getBodyGenerator() {
            return bodyGenerator;
        }

        /* @Override */

        /**
         * @return
         * @deprecated
         */
        public long getLength() {
            return length;
        }

        public long getContentLength() {
            return length;
        }

        /* @Override */
        public FluentStringsMap getParams() {
            return params;
        }

        /* @Override */
        public List<Part> getParts() {
            return parts;
        }

        /* @Override */
        public String getVirtualHost() {
            return virtualHost;
        }

        public FluentStringsMap getQueryParams() {
            return queryParams;
        }

        public ProxyServer getProxyServer() {
            return proxyServer;
        }

        public Realm getRealm() {
            return realm;
        }

        public File getFile() {
            return file;
        }

        public boolean isRedirectEnabled() {
            return (followRedirects != null && followRedirects);
        }

        public boolean isRedirectOverrideSet(){
            return followRedirects != null;
        }

        public PerRequestConfig getPerRequestConfig() {
            return perRequestConfig;
        }

        public long getRangeOffset() {
            return rangeOffset;
        }

        public String getBodyEncoding() {
            return charset;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(url);

            sb.append("\t");
            sb.append(method);
            for (String name : headers.keySet()) {
                sb.append("\t");
                sb.append(name);
                sb.append(":");
                sb.append(headers.getJoinedValue(name, ", "));
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
        request.url = buildUrl(url);
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

    private String buildUrl(String url) {
        URI uri = URI.create(url);
        StringBuilder buildedUrl = new StringBuilder();

        if (uri.getScheme() != null) {
            buildedUrl.append(uri.getScheme());
            buildedUrl.append("://");
        }

        if (uri.getAuthority() != null) {
            buildedUrl.append(uri.getAuthority());
        }
        if (uri.getRawPath() != null) {
            buildedUrl.append(uri.getRawPath());
        } else {
            // AHC-96
            // Let's try to derive it
            if (url.indexOf("://") == -1) {
                String s = buildedUrl.toString();
                url = s + url.substring(uri.getScheme().length() + 1);
                return buildUrl(url);
            } else {
                throw new IllegalArgumentException("Invalid url " + uri.toString());
            }
        }

        if (uri.getRawQuery() != null && !uri.getRawQuery().equals("")) {
            String[] queries = uri.getRawQuery().split("&");
            int pos;
            for (String query : queries) {
                pos = query.indexOf("=");
                if (pos <= 0) {
                    addQueryParameter(query, null);
                } else {
                    try {
                        if (this.useRawUrl) {
                            addQueryParameter(query.substring(0, pos), query.substring(pos + 1));
                        } else {
                            addQueryParameter(URLDecoder.decode(query.substring(0, pos), "UTF-8"), URLDecoder.decode(query.substring(pos + 1), "UTF-8"));
                        }
                    } catch (UnsupportedEncodingException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        return buildedUrl.toString();
    }


    public T setVirtualHost(String virtualHost) {
        request.virtualHost = virtualHost;
        return derived.cast(this);
    }

    public T setHeader(String name, String value) {
        request.headers.replace(name, value);
        return derived.cast(this);
    }

    public T addHeader(String name, String value) {
        if (value == null) {
            logger.warn("Value was null, set to \"\"");
            value = "";
        }

        request.headers.add(name, value);
        return derived.cast(this);
    }

    public T setHeaders(FluentCaseInsensitiveStringsMap headers) {
        request.headers = (headers == null ? new FluentCaseInsensitiveStringsMap() : new FluentCaseInsensitiveStringsMap(headers));
        return derived.cast(this);
    }

    public T setHeaders(Map<String, Collection<String>> headers) {
        request.headers = (headers == null ? new FluentCaseInsensitiveStringsMap() : new FluentCaseInsensitiveStringsMap(headers));
        return derived.cast(this);
    }

    public T setContentLength(int length) {
        request.length = length;
        return derived.cast(this);
    }

    public T addCookie(Cookie cookie) {
        request.cookies.add(cookie);
        return derived.cast(this);
    }

    private void resetParameters() {
        request.params = null;
    }

    private void resetNonMultipartData() {
        request.byteData = null;
        request.stringData = null;
        request.streamData = null;
        request.entityWriter = null;
        request.length = -1;
    }

    private void resetMultipartData() {
        request.parts = null;
    }

    private void checkIfBodyAllowed() {
        if ("GET".equals(request.method) || "HEAD".equals(request.method)) {
            throw new IllegalArgumentException("Can NOT set Body on HTTP Request Method GET nor HEAD.");
        }
    }

    public T setBody(File file) {
        checkIfBodyAllowed();
        request.file = file;
        return derived.cast(this);
    }

    public T setBody(byte[] data) throws IllegalArgumentException {
        checkIfBodyAllowed();
        resetParameters();
        resetNonMultipartData();
        resetMultipartData();
        request.byteData = data;
        return derived.cast(this);
    }

    public T setBody(String data) throws IllegalArgumentException {
        checkIfBodyAllowed();
        resetParameters();
        resetNonMultipartData();
        resetMultipartData();
        request.stringData = data;
        return derived.cast(this);
    }

    public T setBody(InputStream stream) throws IllegalArgumentException {
        checkIfBodyAllowed();
        resetParameters();
        resetNonMultipartData();
        resetMultipartData();
        request.streamData = stream;
        return derived.cast(this);
    }

    public T setBody(EntityWriter dataWriter) {
        return setBody(dataWriter, -1);
    }

    public T setBody(EntityWriter dataWriter, long length) throws IllegalArgumentException {
        checkIfBodyAllowed();
        resetParameters();
        resetNonMultipartData();
        resetMultipartData();
        request.entityWriter = dataWriter;
        request.length = length;
        return derived.cast(this);
    }

    public T setBody(BodyGenerator bodyGenerator) {
        checkIfBodyAllowed();
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

    public T setParameters(Map<String, Collection<String>> parameters) throws IllegalArgumentException {
        resetNonMultipartData();
        resetMultipartData();
        request.params = new FluentStringsMap(parameters);
        return derived.cast(this);
    }

    public T addBodyPart(Part part) throws IllegalArgumentException {
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

    public T setPerRequestConfig(PerRequestConfig perRequestConfig) {
        request.perRequestConfig = perRequestConfig;
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

    public Request build() {
        if ((request.length < 0) && (request.streamData == null) && allowBody(request.getMethod())) {
            // can't concatenate content-length
            String contentLength = request.headers.getFirstValue("Content-Length");

            if (contentLength != null) {
                try {
                    request.length = Long.parseLong(contentLength);
                } catch (NumberFormatException e) {
                    // NoOp -- we wdn't specify length so it will be chunked?
                }
            }
        }
        return request;
    }

    private boolean allowBody(String method) {
        if (method.equalsIgnoreCase("GET") || method.equalsIgnoreCase("OPTIONS")
                && method.equalsIgnoreCase("TRACE")
                && method.equalsIgnoreCase("HEAD")) {
            return false;
        } else {
            return true;
        }
    }

    public T addOrReplaceCookie(Cookie cookie) {
        String cookieKey = cookie.getName();
        boolean replace = false;
        int index = 0;
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
