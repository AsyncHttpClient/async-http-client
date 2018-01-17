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
package org.asynchttpclient.webdav;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.Response;
import org.asynchttpclient.uri.Uri;
import org.w3c.dom.Document;

import java.io.InputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Customized {@link Response} which add support for getting the response's body as an XML document (@link WebDavResponse#getBodyAsXML}
 */
public class WebDavResponse implements Response {

  private final Response response;
  private final Document document;

  WebDavResponse(Response response, Document document) {
    this.response = response;
    this.document = document;
  }

  public int getStatusCode() {
    return response.getStatusCode();
  }

  public String getStatusText() {
    return response.getStatusText();
  }

  @Override
  public byte[] getResponseBodyAsBytes() {
    return response.getResponseBodyAsBytes();
  }

  public ByteBuffer getResponseBodyAsByteBuffer() {
    return response.getResponseBodyAsByteBuffer();
  }

  public InputStream getResponseBodyAsStream() {
    return response.getResponseBodyAsStream();
  }

  public String getResponseBody() {
    return response.getResponseBody();
  }

  public String getResponseBody(Charset charset) {
    return response.getResponseBody(charset);
  }

  public Uri getUri() {
    return response.getUri();
  }

  public String getContentType() {
    return response.getContentType();
  }

  public String getHeader(CharSequence name) {
    return response.getHeader(name);
  }

  public List<String> getHeaders(CharSequence name) {
    return response.getHeaders(name);
  }

  public HttpHeaders getHeaders() {
    return response.getHeaders();
  }

  public boolean isRedirected() {
    return response.isRedirected();
  }

  public List<Cookie> getCookies() {
    return response.getCookies();
  }

  public boolean hasResponseStatus() {
    return response.hasResponseStatus();
  }

  public boolean hasResponseHeaders() {
    return response.hasResponseHeaders();
  }

  public boolean hasResponseBody() {
    return response.hasResponseBody();
  }

  public SocketAddress getRemoteAddress() {
    return response.getRemoteAddress();
  }

  public SocketAddress getLocalAddress() {
    return response.getLocalAddress();
  }

  public Document getBodyAsXML() {
    return document;
  }
}
