/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty;

import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
import org.asynchttpclient.uri.Uri;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.util.HttpUtils.extractCharset;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.asynchttpclient.util.MiscUtils.withDefault;

/**
 * Wrapper around the {@link org.asynchttpclient.Response} API.
 */
public class NettyResponse implements Response {

  private final List<HttpResponseBodyPart> bodyParts;
  private final HttpHeaders headers;
  private final HttpResponseStatus status;
  private List<Cookie> cookies;

  public NettyResponse(HttpResponseStatus status,//
                       HttpHeaders headers,//
                       List<HttpResponseBodyPart> bodyParts) {
    this.bodyParts = bodyParts;
    this.headers = headers;
    this.status = status;
  }

  private List<Cookie> buildCookies() {

    List<String> setCookieHeaders = headers.getAll(SET_COOKIE2);

    if (!isNonEmpty(setCookieHeaders)) {
      setCookieHeaders = headers.getAll(SET_COOKIE);
    }

    if (isNonEmpty(setCookieHeaders)) {
      List<Cookie> cookies = new ArrayList<>(1);
      for (String value : setCookieHeaders) {
        Cookie c = ClientCookieDecoder.STRICT.decode(value);
        if (c != null)
          cookies.add(c);
      }
      return Collections.unmodifiableList(cookies);
    }

    return Collections.emptyList();
  }

  @Override
  public final int getStatusCode() {
    return status.getStatusCode();
  }

  @Override
  public final String getStatusText() {
    return status.getStatusText();
  }

  @Override
  public final Uri getUri() {
    return status.getUri();
  }

  @Override
  public SocketAddress getRemoteAddress() {
    return status.getRemoteAddress();
  }

  @Override
  public SocketAddress getLocalAddress() {
    return status.getLocalAddress();
  }

  @Override
  public final String getContentType() {
    return headers != null ? getHeader(CONTENT_TYPE) : null;
  }

  @Override
  public final String getHeader(CharSequence name) {
    return headers != null ? getHeaders().get(name) : null;
  }

  @Override
  public final List<String> getHeaders(CharSequence name) {
    return headers != null ? getHeaders().getAll(name) : Collections.emptyList();
  }

  @Override
  public final HttpHeaders getHeaders() {
    return headers != null ? headers : EmptyHttpHeaders.INSTANCE;
  }

  @Override
  public final boolean isRedirected() {
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

  @Override
  public List<Cookie> getCookies() {

    if (headers == null) {
      return Collections.emptyList();
    }

    if (cookies == null) {
      cookies = buildCookies();
    }
    return cookies;

  }

  @Override
  public boolean hasResponseStatus() {
    return status != null;
  }

  @Override
  public boolean hasResponseHeaders() {
    return headers != null && !headers.isEmpty();
  }

  @Override
  public boolean hasResponseBody() {
    return isNonEmpty(bodyParts);
  }

  @Override
  public byte[] getResponseBodyAsBytes() {
    return getResponseBodyAsByteBuffer().array();
  }

  @Override
  public ByteBuffer getResponseBodyAsByteBuffer() {

    int length = 0;
    for (HttpResponseBodyPart part : bodyParts)
      length += part.length();

    ByteBuffer target = ByteBuffer.wrap(new byte[length]);
    for (HttpResponseBodyPart part : bodyParts)
      target.put(part.getBodyPartBytes());

    target.flip();
    return target;
  }

  @Override
  public String getResponseBody() {
    return getResponseBody(withDefault(extractCharset(getContentType()), UTF_8));
  }

  @Override
  public String getResponseBody(Charset charset) {
    return new String(getResponseBodyAsBytes(), charset);
  }

  @Override
  public InputStream getResponseBodyAsStream() {
    return new ByteArrayInputStream(getResponseBodyAsBytes());
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getClass().getSimpleName()).append(" {\n")//
            .append("\tstatusCode=").append(getStatusCode()).append("\n")//
            .append("\theaders=\n");

    for (Map.Entry<String, String> header : getHeaders()) {
      sb.append("\t\t").append(header.getKey()).append(": ").append(header.getValue()).append("\n");
    }
    return sb.append("\tbody=\n").append(getResponseBody()).append("\n")//
            .append("}").toString();
  }
}
