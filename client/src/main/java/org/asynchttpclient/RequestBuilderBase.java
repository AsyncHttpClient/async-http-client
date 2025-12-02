/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
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

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.resolver.DefaultNameResolver;
import io.netty.resolver.NameResolver;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.request.body.generator.BodyGenerator;
import org.asynchttpclient.request.body.multipart.Part;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.EnsuresNonNull;
import org.asynchttpclient.util.UriEncoder;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.util.HttpUtils.extractContentTypeCharsetAttribute;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.asynchttpclient.util.MiscUtils.withDefault;

/**
 * Builder for {@link Request}
 *
 * @param <T> the builder type
 */
public abstract class RequestBuilderBase<T extends RequestBuilderBase<T>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestBuilderBase.class);
    private static final Uri DEFAULT_REQUEST_URL = Uri.create("http://localhost");
    public static final NameResolver<InetAddress> DEFAULT_NAME_RESOLVER = new DefaultNameResolver(ImmediateEventExecutor.INSTANCE);
    // builder only fields
    protected UriEncoder uriEncoder;
    protected @Nullable List<Param> queryParams;
    protected @Nullable SignatureCalculator signatureCalculator;

    // request fields
    protected String method;
    protected @Nullable Uri uri;
    protected @Nullable InetAddress address;
    protected @Nullable InetAddress localAddress;
    protected HttpHeaders headers;
    protected @Nullable ArrayList<Cookie> cookies;
    protected byte @Nullable [] byteData;
    protected @Nullable List<byte[]> compositeByteData;
    protected @Nullable String stringData;
    protected @Nullable ByteBuffer byteBufferData;
    protected @Nullable ByteBuf byteBufData;
    protected @Nullable InputStream streamData;
    protected @Nullable BodyGenerator bodyGenerator;
    protected @Nullable List<Param> formParams;
    protected @Nullable List<Part> bodyParts;
    protected @Nullable String virtualHost;
    protected @Nullable ProxyServer proxyServer;
    protected @Nullable Realm realm;
    protected @Nullable File file;
    protected @Nullable Boolean followRedirect;
    protected @Nullable Duration requestTimeout;
    protected @Nullable Duration readTimeout;
    protected long rangeOffset;
    protected @Nullable Charset charset;
    protected ChannelPoolPartitioning channelPoolPartitioning = ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE;
    protected NameResolver<InetAddress> nameResolver = DEFAULT_NAME_RESOLVER;
    // Flag to track if Content-Type was explicitly set by user (should not be modified)
    private boolean contentTypeLocked;

    /**
     * Mark the Content-Type header as explicitly set by the user. When locked, the
     * Content-Type header will not be modified by the client (e.g., charset addition).
     */
    protected final void doContentTypeLock() {
        this.contentTypeLocked = true;
    }

    /**
     * Clear the Content-Type lock, allowing the client to modify the Content-Type header
     * if needed (for example, to add charset when it was auto-generated).
     */
    protected final void resetContentTypeLock() {
        this.contentTypeLocked = false;
    }

    /**
     * Return whether the Content-Type header has been locked as explicitly set by the user.
     */
    protected final boolean isContentTypeLocked() {
        return this.contentTypeLocked;
    }

    protected RequestBuilderBase(String method, boolean disableUrlEncoding) {
        this(method, disableUrlEncoding, true);
    }

    protected RequestBuilderBase(String method, boolean disableUrlEncoding, boolean validateHeaders) {
        this.method = method;
        uriEncoder = UriEncoder.uriEncoder(disableUrlEncoding);
        headers = new DefaultHttpHeaders(validateHeaders);
    }

    protected RequestBuilderBase(Request prototype) {
        this(prototype, false, false);
    }

    protected RequestBuilderBase(Request prototype, boolean disableUrlEncoding, boolean validateHeaders) {
        method = prototype.getMethod();
        uriEncoder = UriEncoder.uriEncoder(disableUrlEncoding);
        uri = prototype.getUri();
        address = prototype.getAddress();
        localAddress = prototype.getLocalAddress();
        headers = new DefaultHttpHeaders(validateHeaders);
        headers.add(prototype.getHeaders());
        // If prototype has Content-Type, consider it as explicitly set
        if (headers.contains(CONTENT_TYPE)) {
            doContentTypeLock();
        }
        if (isNonEmpty(prototype.getCookies())) {
            cookies = new ArrayList<>(prototype.getCookies());
        }
        byteData = prototype.getByteData();
        compositeByteData = prototype.getCompositeByteData();
        stringData = prototype.getStringData();
        byteBufferData = prototype.getByteBufferData();
        byteBufData = prototype.getByteBufData();
        streamData = prototype.getStreamData();
        bodyGenerator = prototype.getBodyGenerator();
        if (isNonEmpty(prototype.getFormParams())) {
            formParams = new ArrayList<>(prototype.getFormParams());
        }
        if (isNonEmpty(prototype.getBodyParts())) {
            bodyParts = new ArrayList<>(prototype.getBodyParts());
        }
        virtualHost = prototype.getVirtualHost();
        proxyServer = prototype.getProxyServer();
        realm = prototype.getRealm();
        file = prototype.getFile();
        followRedirect = prototype.getFollowRedirect();
        requestTimeout = prototype.getRequestTimeout();
        readTimeout = prototype.getReadTimeout();
        rangeOffset = prototype.getRangeOffset();
        charset = prototype.getCharset();
        channelPoolPartitioning = prototype.getChannelPoolPartitioning();
        nameResolver = prototype.getNameResolver();
    }

    @SuppressWarnings("unchecked")
    private T asDerivedType() {
        return (T) this;
    }

    public T setUrl(String url) {
        return setUri(Uri.create(url));
    }

    public T setUri(Uri uri) {
        this.uri = uri;
        return asDerivedType();
    }

    public T setAddress(InetAddress address) {
        this.address = address;
        return asDerivedType();
    }

    public T setLocalAddress(InetAddress address) {
        localAddress = address;
        return asDerivedType();
    }

    public T setVirtualHost(String virtualHost) {
        this.virtualHost = virtualHost;
        return asDerivedType();
    }

    /**
     * Remove all added headers
     *
     * @return {@code this}
     */
    public T clearHeaders() {
        headers.clear();
        resetContentTypeLock();
        return asDerivedType();
    }

    /**
     * @param name  header name
     * @param value header value to set
     * @return {@code this}
     * @see #setHeader(CharSequence, Object)
     */
    public T setHeader(CharSequence name, String value) {
        return setHeader(name, (Object) value);
    }

    /**
     * Set uni-value header for the request
     *
     * @param name  header name
     * @param value header value to set
     * @return {@code this}
     */
    public T setHeader(CharSequence name, Object value) {
        headers.set(name, value);
        if (CONTENT_TYPE.contentEqualsIgnoreCase(name)) {
            doContentTypeLock();
        }
        return asDerivedType();
    }

    /**
     * Set multi-values header for the request
     *
     * @param name   header name
     * @param values {@code Iterable} with multiple header values to set
     * @return {@code this}
     */
    public T setHeader(CharSequence name, Iterable<?> values) {
        headers.set(name, values);
        if (CONTENT_TYPE.contentEqualsIgnoreCase(name)) {
            doContentTypeLock();
        }
        return asDerivedType();
    }

    /**
     * @param name  header name
     * @param value header value to add
     * @return {@code this}
     * @see #addHeader(CharSequence, Object)
     */
    public T addHeader(CharSequence name, String value) {
        return addHeader(name, (Object) value);
    }

    /**
     * Add a header value for the request. If a header with {@code name} was set up for this request already -
     * call will add one more header value and convert it to multi-value header
     *
     * @param name  header name
     * @param value header value to add
     * @return {@code this}
     */
    public T addHeader(CharSequence name, Object value) {
        if (value == null) {
            LOGGER.warn("Value was null, set to \"\"");
            value = "";
        }

        headers.add(name, value);
        if (CONTENT_TYPE.contentEqualsIgnoreCase(name)) {
            doContentTypeLock();
        }
        return asDerivedType();
    }

    /**
     * Add header values for the request. If a header with {@code name} was set up for this request already -
     * call will add more header values and convert it to multi-value header
     *
     * @param name   header name
     * @param values {@code Iterable} with multiple header values to add
     * @return {@code}
     */
    public T addHeader(CharSequence name, Iterable<?> values) {
        headers.add(name, values);
        if (CONTENT_TYPE.contentEqualsIgnoreCase(name)) {
            doContentTypeLock();
        }
        return asDerivedType();
    }

    public T setHeaders(HttpHeaders headers) {
        if (headers == null) {
            this.headers.clear();
        } else {
            this.headers = headers;
            if (headers.contains(CONTENT_TYPE)) {
                doContentTypeLock();
            }
        }
        return asDerivedType();
    }

    /**
     * Set request headers using a map {@code headers} of pair (Header name, Header values)
     * This method could be used to set up multivalued headers
     *
     * @param headers map of header names as the map keys and header values {@link Iterable} as the map values
     * @return {@code this}
     */
    public T setHeaders(Map<? extends CharSequence, ? extends Iterable<?>> headers) {
        clearHeaders();
        if (headers != null) {
            headers.forEach((name, values) -> {
                this.headers.add(name, values);
                if (CONTENT_TYPE.contentEqualsIgnoreCase(name)) {
                    doContentTypeLock();
                }
            });
        }
        return asDerivedType();
    }

    /**
     * Set single-value request headers using a map {@code headers} of pairs (Header name, Header value).
     * To set headers with multiple values use {@link #setHeaders(Map)}
     *
     * @param headers map of header names as the map keys and header values as the map values
     * @return {@code this}
     */
    public T setSingleHeaders(Map<? extends CharSequence, ?> headers) {
        clearHeaders();
        if (headers != null) {
            headers.forEach((name, value) -> {
                this.headers.add(name, value);
                if (CONTENT_TYPE.contentEqualsIgnoreCase(name)) {
                    doContentTypeLock();
                }
            });
        }
        return asDerivedType();
    }

    @EnsuresNonNull("cookies")
    private void lazyInitCookies() {
        if (cookies == null) {
            cookies = new ArrayList<>(3);
        }
    }

    public T setCookies(Collection<Cookie> cookies) {
        this.cookies = new ArrayList<>(cookies);
        return asDerivedType();
    }

    public T addCookie(Cookie cookie) {
        lazyInitCookies();
        cookies.add(cookie);
        return asDerivedType();
    }

    /**
     * Add/replace a cookie based on its name
     *
     * @param cookie the new cookie
     * @return this
     */
    public T addOrReplaceCookie(Cookie cookie) {
        return maybeAddOrReplaceCookie(cookie, true);
    }

    /**
     * Add a cookie based on its name, if it does not exist yet. Cookies that
     * are already set will be ignored.
     *
     * @param cookie the new cookie
     * @return this
     */
    public T addCookieIfUnset(Cookie cookie) {
        return maybeAddOrReplaceCookie(cookie, false);
    }

    private T maybeAddOrReplaceCookie(Cookie cookie, boolean allowReplace) {
        String cookieKey = cookie.name();
        boolean replace = false;
        int index = 0;
        lazyInitCookies();
        for (Cookie c : cookies) {
            if (c.name().equals(cookieKey)) {
                replace = true;
                break;
            }

            index++;
        }
        if (!replace) {
            cookies.add(cookie);
        } else if (allowReplace) {
            cookies.set(index, cookie);
        }
        return asDerivedType();
    }

    public void resetCookies() {
        if (cookies != null) {
            cookies.clear();
        }
    }

    public void resetQuery() {
        queryParams = null;
        if (uri != null) {
            uri = uri.withNewQuery(null);
        }
    }

    public void resetFormParams() {
        formParams = null;
    }

    public void resetNonMultipartData() {
        byteData = null;
        compositeByteData = null;
        byteBufferData = null;
        byteBufData = null;
        stringData = null;
        streamData = null;
        bodyGenerator = null;
    }

    public void resetMultipartData() {
        bodyParts = null;
    }

    public T setBody(File file) {
        this.file = file;
        return asDerivedType();
    }

    private void resetBody() {
        resetFormParams();
        resetNonMultipartData();
        resetMultipartData();
    }

    public T setBody(byte[] data) {
        resetBody();
        byteData = data;
        return asDerivedType();
    }

    public T setBody(List<byte[]> data) {
        resetBody();
        compositeByteData = data;
        return asDerivedType();
    }

    public T setBody(String data) {
        resetBody();
        stringData = data;
        return asDerivedType();
    }

    public T setBody(ByteBuffer data) {
        resetBody();
        byteBufferData = data;
        return asDerivedType();
    }

    public T setBody(ByteBuf data) {
        resetBody();
        byteBufData = data;
        return asDerivedType();
    }

    public T setBody(InputStream stream) {
        resetBody();
        streamData = stream;
        return asDerivedType();
    }

    public T setBody(BodyGenerator bodyGenerator) {
        this.bodyGenerator = bodyGenerator;
        return asDerivedType();
    }

    @EnsuresNonNull("queryParams")
    public T addQueryParam(String name, String value) {
        if (queryParams == null) {
            queryParams = new ArrayList<>(1);
        }
        queryParams.add(new Param(name, value));
        return asDerivedType();
    }

    @EnsuresNonNull("queryParams")
    public T addQueryParams(List<Param> params) {
        if (queryParams == null) {
            queryParams = params;
        } else {
            queryParams.addAll(params);
        }
        return asDerivedType();
    }

    public T setQueryParams(Map<String, List<String>> map) {
        return setQueryParams(Param.map2ParamList(map));
    }

    public T setQueryParams(@Nullable List<Param> params) {
        // reset existing query
        if (uri != null && isNonEmpty(uri.getQuery())) {
            uri = uri.withNewQuery(null);
        }
        queryParams = params;
        return asDerivedType();
    }

    @EnsuresNonNull("formParams")
    public T addFormParam(String name, String value) {
        resetNonMultipartData();
        resetMultipartData();
        if (formParams == null) {
            formParams = new ArrayList<>(1);
        }
        formParams.add(new Param(name, value));
        return asDerivedType();
    }

    public T setFormParams(Map<String, List<String>> map) {
        return setFormParams(Param.map2ParamList(map));
    }

    public T setFormParams(@Nullable List<Param> params) {
        resetNonMultipartData();
        resetMultipartData();
        formParams = params;
        return asDerivedType();
    }

    @EnsuresNonNull("bodyParts")
    public T addBodyPart(Part bodyPart) {
        resetFormParams();
        resetNonMultipartData();
        if (bodyParts == null) {
            bodyParts = new ArrayList<>();
        }
        bodyParts.add(bodyPart);
        return asDerivedType();
    }

    @EnsuresNonNull("bodyParts")
    public T setBodyParts(List<Part> bodyParts) {
        this.bodyParts = new ArrayList<>(bodyParts);
        return asDerivedType();
    }

    public T setProxyServer(ProxyServer proxyServer) {
        this.proxyServer = proxyServer;
        return asDerivedType();
    }

    public T setProxyServer(ProxyServer.Builder proxyServerBuilder) {
        proxyServer = proxyServerBuilder.build();
        return asDerivedType();
    }

    public T setRealm(Realm.Builder realm) {
        this.realm = realm.build();
        return asDerivedType();
    }

    public T setRealm(Realm realm) {
        this.realm = realm;
        return asDerivedType();
    }

    public T setFollowRedirect(boolean followRedirect) {
        this.followRedirect = followRedirect;
        return asDerivedType();
    }

    public T setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        return asDerivedType();
    }

    public T setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        return asDerivedType();
    }

    public T setRangeOffset(long rangeOffset) {
        this.rangeOffset = rangeOffset;
        return asDerivedType();
    }

    public T setMethod(String method) {
        this.method = method;
        return asDerivedType();
    }

    public T setCharset(Charset charset) {
        this.charset = charset;
        return asDerivedType();
    }

    public T setChannelPoolPartitioning(ChannelPoolPartitioning channelPoolPartitioning) {
        this.channelPoolPartitioning = channelPoolPartitioning;
        return asDerivedType();
    }

    public T setNameResolver(NameResolver<InetAddress> nameResolver) {
        this.nameResolver = nameResolver;
        return asDerivedType();
    }

    public T setSignatureCalculator(@Nullable SignatureCalculator signatureCalculator) {
        this.signatureCalculator = signatureCalculator;
        return asDerivedType();
    }

    private RequestBuilderBase<?> executeSignatureCalculator() {
        if (signatureCalculator == null) {
            return this;
        }

        // build a first version of the request, without signatureCalculator in play
        RequestBuilder rb = new RequestBuilder(method);
        // make copy of mutable collections, so we don't risk affecting
        // original RequestBuilder
        // call setFormParams first as it resets other fields
        if (formParams != null) {
            rb.setFormParams(formParams);
        }
        if (headers != null) {
            rb.headers.add(headers);
        }
        if (cookies != null) {
            rb.setCookies(cookies);
        }
        if (bodyParts != null) {
            rb.setBodyParts(bodyParts);
        }

        // copy all other fields
        // but rb.signatureCalculator, that's the whole point here
        rb.uriEncoder = uriEncoder;
        rb.queryParams = queryParams;
        rb.uri = uri;
        rb.address = address;
        rb.localAddress = localAddress;
        rb.byteData = byteData;
        rb.compositeByteData = compositeByteData;
        rb.stringData = stringData;
        rb.byteBufferData = byteBufferData;
        rb.byteBufData = byteBufData;
        rb.streamData = streamData;
        rb.bodyGenerator = bodyGenerator;
        rb.virtualHost = virtualHost;
        rb.proxyServer = proxyServer;
        rb.realm = realm;
        rb.file = file;
        rb.followRedirect = followRedirect;
        rb.requestTimeout = requestTimeout;
        rb.rangeOffset = rangeOffset;
        rb.charset = charset;
        rb.channelPoolPartitioning = channelPoolPartitioning;
        rb.nameResolver = nameResolver;
        Request unsignedRequest = rb.build();
        signatureCalculator.calculateAndAddSignature(unsignedRequest, rb);
        return rb;
    }

    @EnsuresNonNull("charset")
    private void updateCharset() {
        String contentTypeHeader = headers.get(CONTENT_TYPE);
        Charset contentTypeCharset = extractContentTypeCharsetAttribute(contentTypeHeader);
        charset = withDefault(contentTypeCharset, withDefault(charset, UTF_8));
        // Only add charset if Content-Type was not explicitly set by user
        if (!isContentTypeLocked() && contentTypeHeader != null && contentTypeHeader.regionMatches(true, 0, "text/", 0, 5) && contentTypeCharset == null) {
            // add explicit charset to content-type header
            headers.set(CONTENT_TYPE, contentTypeHeader + "; charset=" + charset.name());
        }
    }

    private Uri computeUri() {

        Uri tempUri = uri;
        if (tempUri == null) {
            LOGGER.debug("setUrl hasn't been invoked. Using {}", DEFAULT_REQUEST_URL);
            tempUri = DEFAULT_REQUEST_URL;
        } else {
            Uri.validateSupportedScheme(tempUri);
        }

        return uriEncoder.encode(tempUri, queryParams);
    }

    public Request build() {
        updateCharset();
        RequestBuilderBase<?> rb = executeSignatureCalculator();
        Uri finalUri = rb.computeUri();

        // make copies of mutable internal collections
        List<Cookie> cookiesCopy = rb.cookies == null ? Collections.emptyList() : new ArrayList<>(rb.cookies);
        List<Param> formParamsCopy = rb.formParams == null ? Collections.emptyList() : new ArrayList<>(rb.formParams);
        List<Part> bodyPartsCopy = rb.bodyParts == null ? Collections.emptyList() : new ArrayList<>(rb.bodyParts);

        return new DefaultRequest(rb.method,
                finalUri,
                rb.address,
                rb.localAddress,
                rb.headers,
                cookiesCopy,
                rb.byteData,
                rb.compositeByteData,
                rb.stringData,
                rb.byteBufferData,
                rb.byteBufData,
                rb.streamData,
                rb.bodyGenerator,
                formParamsCopy,
                bodyPartsCopy,
                rb.virtualHost,
                rb.proxyServer,
                rb.realm,
                rb.file,
                rb.followRedirect,
                rb.requestTimeout,
                rb.readTimeout,
                rb.rangeOffset,
                rb.charset,
                rb.channelPoolPartitioning,
                rb.nameResolver);
    }
}
