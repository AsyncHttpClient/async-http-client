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
import org.asynchttpclient.request.body.generator.ReactiveStreamsBodyGenerator;
import org.asynchttpclient.request.body.multipart.Part;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.UriEncoder;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.*;

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

  private final static Logger LOGGER = LoggerFactory.getLogger(RequestBuilderBase.class);
  private static final Uri DEFAULT_REQUEST_URL = Uri.create("http://localhost");
  public static NameResolver<InetAddress> DEFAULT_NAME_RESOLVER = new DefaultNameResolver(ImmediateEventExecutor.INSTANCE);
  // builder only fields
  protected UriEncoder uriEncoder;
  protected List<Param> queryParams;
  protected SignatureCalculator signatureCalculator;

  // request fields
  protected String method;
  protected Uri uri;
  protected InetAddress address;
  protected InetAddress localAddress;
  protected HttpHeaders headers;
  protected ArrayList<Cookie> cookies;
  protected byte[] byteData;
  protected List<byte[]> compositeByteData;
  protected String stringData;
  protected ByteBuffer byteBufferData;
  protected InputStream streamData;
  protected BodyGenerator bodyGenerator;
  protected List<Param> formParams;
  protected List<Part> bodyParts;
  protected String virtualHost;
  protected ProxyServer proxyServer;
  protected Realm realm;
  protected File file;
  protected Boolean followRedirect;
  protected int requestTimeout;
  protected int readTimeout;
  protected long rangeOffset;
  protected Charset charset;
  protected ChannelPoolPartitioning channelPoolPartitioning = ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE;
  protected NameResolver<InetAddress> nameResolver = DEFAULT_NAME_RESOLVER;

  protected RequestBuilderBase(String method, boolean disableUrlEncoding) {
    this(method, disableUrlEncoding, true);
  }

  protected RequestBuilderBase(String method, boolean disableUrlEncoding, boolean validateHeaders) {
    this.method = method;
    this.uriEncoder = UriEncoder.uriEncoder(disableUrlEncoding);
    this.headers = new DefaultHttpHeaders(validateHeaders);
  }

  protected RequestBuilderBase(Request prototype) {
    this(prototype, false, false);
  }

  protected RequestBuilderBase(Request prototype, boolean disableUrlEncoding, boolean validateHeaders) {
    this.method = prototype.getMethod();
    this.uriEncoder = UriEncoder.uriEncoder(disableUrlEncoding);
    this.uri = prototype.getUri();
    this.address = prototype.getAddress();
    this.localAddress = prototype.getLocalAddress();
    this.headers = new DefaultHttpHeaders(validateHeaders);
    this.headers.add(prototype.getHeaders());
    if (isNonEmpty(prototype.getCookies())) {
      this.cookies = new ArrayList<>(prototype.getCookies());
    }
    this.byteData = prototype.getByteData();
    this.compositeByteData = prototype.getCompositeByteData();
    this.stringData = prototype.getStringData();
    this.byteBufferData = prototype.getByteBufferData();
    this.streamData = prototype.getStreamData();
    this.bodyGenerator = prototype.getBodyGenerator();
    if (isNonEmpty(prototype.getFormParams())) {
      this.formParams = new ArrayList<>(prototype.getFormParams());
    }
    if (isNonEmpty(prototype.getBodyParts())) {
      this.bodyParts = new ArrayList<>(prototype.getBodyParts());
    }
    this.virtualHost = prototype.getVirtualHost();
    this.proxyServer = prototype.getProxyServer();
    this.realm = prototype.getRealm();
    this.file = prototype.getFile();
    this.followRedirect = prototype.getFollowRedirect();
    this.requestTimeout = prototype.getRequestTimeout();
    this.readTimeout = prototype.getReadTimeout();
    this.rangeOffset = prototype.getRangeOffset();
    this.charset = prototype.getCharset();
    this.channelPoolPartitioning = prototype.getChannelPoolPartitioning();
    this.nameResolver = prototype.getNameResolver();
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
    this.localAddress = address;
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
    this.headers.clear();
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
    this.headers.set(name, value);
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
    this.headers.set(name, values);
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
   * Add a header value for the request. If a header with {@code name} was setup for this request already -
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

    this.headers.add(name, value);
    return asDerivedType();
  }

  /**
   * Add header values for the request. If a header with {@code name} was setup for this request already -
   * call will add more header values and convert it to multi-value header
   *
   * @param name   header name
   * @param values {@code Iterable} with multiple header values to add
   * @return {@code}
   */
  public T addHeader(CharSequence name, Iterable<?> values) {
    this.headers.add(name, values);
    return asDerivedType();
  }

  public T setHeaders(HttpHeaders headers) {
    if (headers == null)
      this.headers.clear();
    else
      this.headers = headers;
    return asDerivedType();
  }

  /**
   * Set request headers using a map {@code headers} of pair (Header name, Header values)
   * This method could be used to setup multi-valued headers
   *
   * @param headers map of header names as the map keys and header values {@link Iterable} as the map values
   * @return {@code this}
   */
  public T setHeaders(Map<? extends CharSequence, ? extends Iterable<?>> headers) {
    clearHeaders();
    if (headers != null) {
      headers.forEach((name, values) -> this.headers.add(name, values));
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
      headers.forEach((name, value) -> this.headers.add(name, value));
    }
    return asDerivedType();
  }

  private void lazyInitCookies() {
    if (this.cookies == null)
      this.cookies = new ArrayList<>(3);
  }

  public T setCookies(Collection<Cookie> cookies) {
    this.cookies = new ArrayList<>(cookies);
    return asDerivedType();
  }

  public T addCookie(Cookie cookie) {
    lazyInitCookies();
    this.cookies.add(cookie);
    return asDerivedType();
  }

  /**
   * Add/replace a cookie based on its name
   * @param cookie the new cookie
   * @return this
   */
  public T addOrReplaceCookie(Cookie cookie) {
    String cookieKey = cookie.name();
    boolean replace = false;
    int index = 0;
    lazyInitCookies();
    for (Cookie c : this.cookies) {
      if (c.name().equals(cookieKey)) {
        replace = true;
        break;
      }

      index++;
    }
    if (replace)
      this.cookies.set(index, cookie);
    else
      this.cookies.add(cookie);
    return asDerivedType();
  }

  public void resetCookies() {
    if (this.cookies != null)
      this.cookies.clear();
  }

  public void resetQuery() {
    queryParams = null;
    if (this.uri != null)
      this.uri = this.uri.withNewQuery(null);
  }

  public void resetFormParams() {
    this.formParams = null;
  }

  public void resetNonMultipartData() {
    this.byteData = null;
    this.compositeByteData = null;
    this.byteBufferData = null;
    this.stringData = null;
    this.streamData = null;
    this.bodyGenerator = null;
  }

  public void resetMultipartData() {
    this.bodyParts = null;
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
    this.byteData = data;
    return asDerivedType();
  }

  public T setBody(List<byte[]> data) {
    resetBody();
    this.compositeByteData = data;
    return asDerivedType();
  }

  public T setBody(String data) {
    resetBody();
    this.stringData = data;
    return asDerivedType();
  }

  public T setBody(ByteBuffer data) {
    resetBody();
    this.byteBufferData = data;
    return asDerivedType();
  }

  public T setBody(InputStream stream) {
    resetBody();
    this.streamData = stream;
    return asDerivedType();
  }

  public T setBody(Publisher<ByteBuf> publisher) {
    return setBody(publisher, -1L);
  }

  public T setBody(Publisher<ByteBuf> publisher, long contentLength) {
    return setBody(new ReactiveStreamsBodyGenerator(publisher, contentLength));
  }

  public T setBody(BodyGenerator bodyGenerator) {
    this.bodyGenerator = bodyGenerator;
    return asDerivedType();
  }

  public T addQueryParam(String name, String value) {
    if (queryParams == null)
      queryParams = new ArrayList<>(1);
    queryParams.add(new Param(name, value));
    return asDerivedType();
  }

  public T addQueryParams(List<Param> params) {
    if (queryParams == null)
      queryParams = params;
    else
      queryParams.addAll(params);
    return asDerivedType();
  }

  public T setQueryParams(Map<String, List<String>> map) {
    return setQueryParams(Param.map2ParamList(map));
  }

  public T setQueryParams(List<Param> params) {
    // reset existing query
    if (this.uri != null && isNonEmpty(this.uri.getQuery()))
      this.uri = this.uri.withNewQuery(null);
    queryParams = params;
    return asDerivedType();
  }

  public T addFormParam(String name, String value) {
    resetNonMultipartData();
    resetMultipartData();
    if (this.formParams == null)
      this.formParams = new ArrayList<>(1);
    this.formParams.add(new Param(name, value));
    return asDerivedType();
  }

  public T setFormParams(Map<String, List<String>> map) {
    return setFormParams(Param.map2ParamList(map));
  }

  public T setFormParams(List<Param> params) {
    resetNonMultipartData();
    resetMultipartData();
    this.formParams = params;
    return asDerivedType();
  }

  public T addBodyPart(Part bodyPart) {
    resetFormParams();
    resetNonMultipartData();
    if (this.bodyParts == null)
      this.bodyParts = new ArrayList<>();
    this.bodyParts.add(bodyPart);
    return asDerivedType();
  }

  public T setBodyParts(List<Part> bodyParts) {
    this.bodyParts = new ArrayList<>(bodyParts);
    return asDerivedType();
  }

  public T setProxyServer(ProxyServer proxyServer) {
    this.proxyServer = proxyServer;
    return asDerivedType();
  }

  public T setProxyServer(ProxyServer.Builder proxyServerBuilder) {
    this.proxyServer = proxyServerBuilder.build();
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

  public T setRequestTimeout(int requestTimeout) {
    this.requestTimeout = requestTimeout;
    return asDerivedType();
  }

  public T setReadTimeout(int readTimeout) {
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

  public T setSignatureCalculator(SignatureCalculator signatureCalculator) {
    this.signatureCalculator = signatureCalculator;
    return asDerivedType();
  }

  private RequestBuilderBase<?> executeSignatureCalculator() {
    if (signatureCalculator == null)
      return this;

    // build a first version of the request, without signatureCalculator in play
    RequestBuilder rb = new RequestBuilder(this.method);
    // make copy of mutable collections so we don't risk affecting
    // original RequestBuilder
    // call setFormParams first as it resets other fields
    if (this.formParams != null)
      rb.setFormParams(this.formParams);
    if (this.headers != null)
      rb.headers.add(this.headers);
    if (this.cookies != null)
      rb.setCookies(this.cookies);
    if (this.bodyParts != null)
      rb.setBodyParts(this.bodyParts);

    // copy all other fields
    // but rb.signatureCalculator, that's the whole point here
    rb.uriEncoder = this.uriEncoder;
    rb.queryParams = this.queryParams;
    rb.uri = this.uri;
    rb.address = this.address;
    rb.localAddress = this.localAddress;
    rb.byteData = this.byteData;
    rb.compositeByteData = this.compositeByteData;
    rb.stringData = this.stringData;
    rb.byteBufferData = this.byteBufferData;
    rb.streamData = this.streamData;
    rb.bodyGenerator = this.bodyGenerator;
    rb.virtualHost = this.virtualHost;
    rb.proxyServer = this.proxyServer;
    rb.realm = this.realm;
    rb.file = this.file;
    rb.followRedirect = this.followRedirect;
    rb.requestTimeout = this.requestTimeout;
    rb.rangeOffset = this.rangeOffset;
    rb.charset = this.charset;
    rb.channelPoolPartitioning = this.channelPoolPartitioning;
    rb.nameResolver = this.nameResolver;
    Request unsignedRequest = rb.build();
    signatureCalculator.calculateAndAddSignature(unsignedRequest, rb);
    return rb;
  }

  private void updateCharset() {
    String contentTypeHeader = headers.get(CONTENT_TYPE);
    Charset contentTypeCharset = extractContentTypeCharsetAttribute(contentTypeHeader);
    charset = withDefault(contentTypeCharset, withDefault(charset, UTF_8));
    if (contentTypeHeader != null && contentTypeHeader.regionMatches(true, 0, "text/", 0, 5) && contentTypeCharset == null) {
      // add explicit charset to content-type header
      headers.set(CONTENT_TYPE, contentTypeHeader + "; charset=" + charset.name());
    }
  }

  private Uri computeUri() {

    Uri tempUri = this.uri;
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
