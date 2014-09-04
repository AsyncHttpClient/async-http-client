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

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.multipart.Part;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.asynchttpclient.util.QueryComputer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Builder for {@link Request}
 * 
 * @param <T>
 */
public abstract class RequestBuilderBase<T extends RequestBuilderBase<T>> {
    private final static Logger logger = LoggerFactory.getLogger(RequestBuilderBase.class);

    private static final Uri DEFAULT_REQUEST_URL = Uri.create("http://localhost");

    private static final class RequestImpl implements Request {
        private String method;
        private Uri uri;
        private InetAddress address;
        private InetAddress localAddress;
        private FluentCaseInsensitiveStringsMap headers = new FluentCaseInsensitiveStringsMap();
        private ArrayList<Cookie> cookies;
        private byte[] byteData;
        private String stringData;
        private InputStream streamData;
        private BodyGenerator bodyGenerator;
        private List<Param> formParams;
        private List<Part> parts;
        private String virtualHost;
        private long length = -1;
        public ProxyServer proxyServer;
        private Realm realm;
        private File file;
        private Boolean followRedirect;
        private int requestTimeoutInMs;
        private long rangeOffset;
        public String charset;
        private ConnectionPoolPartitioning connectionPoolPartitioning = PerHostConnectionPoolPartitioning.INSTANCE;
        private List<Param> queryParams;

        public RequestImpl() {
        }

        public RequestImpl(Request prototype) {
            if (prototype != null) {
                this.method = prototype.getMethod();
                this.uri = prototype.getUri();
                this.address = prototype.getInetAddress();
                this.localAddress = prototype.getLocalAddress();
                this.headers = new FluentCaseInsensitiveStringsMap(prototype.getHeaders());
                this.cookies = new ArrayList<Cookie>(prototype.getCookies());
                this.byteData = prototype.getByteData();
                this.stringData = prototype.getStringData();
                this.streamData = prototype.getStreamData();
                this.bodyGenerator = prototype.getBodyGenerator();
                this.formParams = prototype.getFormParams() == null ? null : new ArrayList<Param>(prototype.getFormParams());
                this.parts = prototype.getParts() == null ? null : new ArrayList<Part>(prototype.getParts());
                this.virtualHost = prototype.getVirtualHost();
                this.length = prototype.getContentLength();
                this.proxyServer = prototype.getProxyServer();
                this.realm = prototype.getRealm();
                this.file = prototype.getFile();
                this.followRedirect = prototype.getFollowRedirect();
                this.requestTimeoutInMs = prototype.getRequestTimeoutInMs();
                this.rangeOffset = prototype.getRangeOffset();
                this.charset = prototype.getBodyEncoding();
                this.connectionPoolPartitioning = prototype.getConnectionPoolPartitioning();
            }
        }

        @Override
        public String getUrl() {
            return uri.toUrl();
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

        @Override
        public Uri getUri() {
            return uri;
        }

        @Override
        public FluentCaseInsensitiveStringsMap getHeaders() {
            return headers;
        }

        @Override
        public Collection<Cookie> getCookies() {
            return cookies != null ? Collections.unmodifiableCollection(cookies) : Collections.<Cookie> emptyList();
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
        public List<Param> getFormParams() {
            return formParams != null ? formParams : Collections.<Param> emptyList();
        }

        @Override
        public List<Part> getParts() {
            return parts != null ? parts : Collections.<Part> emptyList();
        }

        @Override
        public String getVirtualHost() {
            return virtualHost;
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
        public Boolean getFollowRedirect() {
            return followRedirect;
        }

        @Override
        public int getRequestTimeoutInMs() {
            return requestTimeoutInMs;
        }

        @Override
        public long getRangeOffset() {
            return rangeOffset;
        }

        @Override
        public String getBodyEncoding() {
            return charset;
        }

        @Override
        public ConnectionPoolPartitioning getConnectionPoolPartitioning() {
            return connectionPoolPartitioning;
        }

        @Override
        public List<Param> getQueryParams() {
            if (queryParams == null)
                // lazy load
                if (isNonEmpty(uri.getQuery())) {
                    queryParams = new ArrayList<Param>(1);
                    for (String queryStringParam : uri.getQuery().split("&")) {
                        int pos = queryStringParam.indexOf('=');
                        if (pos <= 0)
                            queryParams.add(new Param(queryStringParam, null));
                        else
                            queryParams.add(new Param(queryStringParam.substring(0, pos), queryStringParam.substring(pos + 1)));
                    }
                } else
                    queryParams = Collections.emptyList();
            return queryParams;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(getUrl());

            sb.append("\t");
            sb.append(method);
            sb.append("\theaders:");
            if (isNonEmpty(headers)) {
                for (String name : headers.keySet()) {
                    sb.append("\t");
                    sb.append(name);
                    sb.append(":");
                    sb.append(headers.getJoinedValue(name, ", "));
                }
            }
            if (isNonEmpty(formParams)) {
                sb.append("\tformParams:");
                for (Param param : formParams) {
                    sb.append("\t");
                    sb.append(param.getName());
                    sb.append(":");
                    sb.append(param.getValue());
                }
            }

            return sb.toString();
        }
    }

    private final Class<T> derived;
    protected final RequestImpl request;
    protected QueryComputer queryComputer;
    protected List<Param> queryParams;
    protected SignatureCalculator signatureCalculator;

    protected RequestBuilderBase(Class<T> derived, String method, boolean disableUrlEncoding) {
        this(derived, method, QueryComputer.queryComputer(disableUrlEncoding));
    }

    protected RequestBuilderBase(Class<T> derived, String method, QueryComputer queryComputer) {
        this.derived = derived;
        request = new RequestImpl();
        request.method = method;
        this.queryComputer = queryComputer;
    }

    protected RequestBuilderBase(Class<T> derived, Request prototype) {
        this(derived, prototype, QueryComputer.URL_ENCODING_ENABLED_QUERY_COMPUTER);
    }

    protected RequestBuilderBase(Class<T> derived, Request prototype, QueryComputer queryComputer) {
        this.derived = derived;
        request = new RequestImpl(prototype);
        this.queryComputer = queryComputer;
    }
    
    public T setUrl(String url) {
        return setUri(Uri.create(url));
    }

    public T setUri(Uri uri) {
        request.uri = uri;
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

    public T setVirtualHost(String virtualHost) {
        request.virtualHost = virtualHost;
        return derived.cast(this);
    }

    public T setHeader(String name, String value) {
        request.headers.replaceWith(name, value);
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

    private void lazyInitCookies() {
        if (request.cookies == null)
            request.cookies = new ArrayList<Cookie>(3);
    }

    public T setCookies(Collection<Cookie> cookies) {
        request.cookies = new ArrayList<Cookie>(cookies);
        return derived.cast(this);
    }

    public T addCookie(Cookie cookie) {
        lazyInitCookies();
        request.cookies.add(cookie);
        return derived.cast(this);
    }

    public T addOrReplaceCookie(Cookie cookie) {
        String cookieKey = cookie.getName();
        boolean replace = false;
        int index = 0;
        lazyInitCookies();
        for (Cookie c : request.cookies) {
            if (c.getName().equals(cookieKey)) {
                replace = true;
                break;
            }

            index++;
        }
        if (replace)
            request.cookies.set(index, cookie);
        else
            request.cookies.add(cookie);
        return derived.cast(this);
    }
    
    public void resetCookies() {
        if (request.cookies != null)
            request.cookies.clear();
    }
    
    public void resetQuery() {
        queryParams = null;
        request.uri = request.uri.withNewQuery(null);
    }
    
    public void resetFormParams() {
        request.formParams = null;
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

    public T setBody(byte[] data) {
        resetFormParams();
        resetNonMultipartData();
        resetMultipartData();
        request.byteData = data;
        return derived.cast(this);
    }

    public T setBody(String data) {
        resetFormParams();
        resetNonMultipartData();
        resetMultipartData();
        request.stringData = data;
        return derived.cast(this);
    }

    public T setBody(InputStream stream) {
        resetFormParams();
        resetNonMultipartData();
        resetMultipartData();
        request.streamData = stream;
        return derived.cast(this);
    }

    public T setBody(BodyGenerator bodyGenerator) {
        request.bodyGenerator = bodyGenerator;
        return derived.cast(this);
    }

    public T addQueryParam(String name, String value) {
        if (queryParams == null) {
            queryParams = new ArrayList<Param>(1);
        }
        queryParams.add(new Param(name, value));
        return derived.cast(this);
    }

    public T addQueryParams(List<Param> queryParams) {
        for (Param queryParam: queryParams)
            addQueryParam(queryParam.getName(), queryParam.getValue());
        return derived.cast(this);
    }

    private List<Param> map2ParamList(Map<String, List<String>> map) {
        if (map == null)
            return null;

        List<Param> params = new ArrayList<Param>(map.size());
        for (Map.Entry<String, List<String>> entries : map.entrySet()) {
            String name = entries.getKey();
            for (String value : entries.getValue())
                params.add(new Param(name, value));
        }
        return params;
    }
    
    public T setQueryParams(Map<String, List<String>> map) {
        return setQueryParams(map2ParamList(map));
    }

    public T setQueryParams(List<Param> params) {
        queryParams = params;
        return derived.cast(this);
    }
    
    public T addFormParam(String name, String value) {
        resetNonMultipartData();
        resetMultipartData();
        if (request.formParams == null)
            request.formParams = new ArrayList<Param>(1);
        request.formParams.add(new Param(name, value));
        return derived.cast(this);
    }

    public T setFormParams(Map<String, List<String>> map) {
        return setFormParams(map2ParamList(map));
    }
    public T setFormParams(List<Param> params) {
        resetNonMultipartData();
        resetMultipartData();
        request.formParams = params;
        return derived.cast(this);
    }

    public T addBodyPart(Part part) {
        resetFormParams();
        resetNonMultipartData();
        if (request.parts == null)
            request.parts = new ArrayList<Part>();
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

    public T setFollowRedirect(boolean followRedirect) {
        request.followRedirect = followRedirect;
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

    public T setConnectionPoolPartitioning(ConnectionPoolPartitioning connectionPoolPartitioning) {
        request.connectionPoolPartitioning = connectionPoolPartitioning;
        return derived.cast(this);
    }

    public T setSignatureCalculator(SignatureCalculator signatureCalculator) {
        this.signatureCalculator = signatureCalculator;
        return derived.cast(this);
    }

    private void executeSignatureCalculator() {
        /* Let's first calculate and inject signature, before finalizing actual build
         * (order does not matter with current implementation but may in future)
         */
        if (signatureCalculator != null) {
            signatureCalculator.calculateAndAddSignature(request, this);
        }
    }
    
    private void computeRequestCharset() {
        if (request.charset == null) {
            try {
                final String contentType = request.headers.getFirstValue("Content-Type");
                if (contentType != null) {
                    final String charset = AsyncHttpProviderUtils.parseCharset(contentType);
                    if (charset != null) {
                        // ensure that if charset is provided with the Content-Type header,
                        // we propagate that down to the charset of the Request object
                        request.charset = charset;
                    }
                }
            } catch (Throwable e) {
                // NoOp -- we can't fix the Content-Type or charset from here
            }
        }
    }
    
    private void computeRequestLength() {
        if (request.length < 0 && request.streamData == null) {
            // can't concatenate content-length
            final String contentLength = request.headers.getFirstValue("Content-Length");

            if (contentLength != null) {
                try {
                    request.length = Long.parseLong(contentLength);
                } catch (NumberFormatException e) {
                    // NoOp -- we wdn't specify length so it will be chunked?
                }
            }
        }
    }

    private void computeFinalUri() {

        if (request.uri == null) {
            logger.debug("setUrl hasn't been invoked. Using {}", DEFAULT_REQUEST_URL);
            request.uri = DEFAULT_REQUEST_URL;
        }

        AsyncHttpProviderUtils.validateSupportedScheme(request.uri);

        String newQuery = queryComputer.computeFullQueryString(request.uri.getQuery(), queryParams);

        request.uri = request.uri.withNewQuery(newQuery);
    }

    public Request build() {
        computeFinalUri();
        executeSignatureCalculator();
        computeRequestCharset();
        computeRequestLength();
        return request;
    }
}

