/*
 * Copyright 2010 Ning, Inc.
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
 *
 */
package org.asynchttpclient;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.resolver.NameResolver;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.request.body.generator.BodyGenerator;
import org.asynchttpclient.request.body.multipart.Part;
import org.asynchttpclient.uri.Uri;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;

/**
 * The Request class can be used to construct HTTP request:
 * <blockquote><pre>
 *   Request r = new RequestBuilder()
 *      .setUrl("url")
 *      .setRealm(
 *          new Realm.Builder("principal", "password")
 *              .setRealmName("MyRealm")
 *              .setScheme(Realm.AuthScheme.BASIC)
 *      ).build();
 * </pre></blockquote>
 */
public interface Request {

  /**
   * @return the request's HTTP method (GET, POST, etc.)
   */
  String getMethod();

  /**
   * @return the uri
   */
  Uri getUri();

  /**
   * @return the url (the uri's String form)
   */
  String getUrl();

  /**
   * @return the InetAddress to be used to bypass uri's hostname resolution
   */
  InetAddress getAddress();

  /**
   * @return the local address to bind from
   */
  InetAddress getLocalAddress();

  /**
   * @return the HTTP headers
   */
  HttpHeaders getHeaders();

  /**
   * @return the HTTP cookies
   */
  List<Cookie> getCookies();

  /**
   * @return the request's body byte array (only non null if it was set this way)
   */
  byte[] getByteData();

  /**
   * @return the request's body array of byte arrays (only non null if it was set this way)
   */
  List<byte[]> getCompositeByteData();

  /**
   * @return the request's body string (only non null if it was set this way)
   */
  String getStringData();

  /**
   * @return the request's body ByteBuffer (only non null if it was set this way)
   */
  ByteBuffer getByteBufferData();

  /**
   * @return the request's body InputStream (only non null if it was set this way)
   */
  InputStream getStreamData();

  /**
   * @return the request's body BodyGenerator (only non null if it was set this way)
   */
  BodyGenerator getBodyGenerator();

  /**
   * @return the request's form parameters
   */
  List<Param> getFormParams();

  /**
   * @return the multipart parts
   */
  List<Part> getBodyParts();

  /**
   * @return the virtual host to connect to
   */
  String getVirtualHost();

  /**
   * @return the query params resolved from the url/uri
   */
  List<Param> getQueryParams();

  /**
   * @return the proxy server to be used to perform this request (overrides the one defined in config)
   */
  ProxyServer getProxyServer();

  /**
   * @return the realm to be used to perform this request (overrides the one defined in config)
   */
  Realm getRealm();

  /**
   * @return the file to be uploaded
   */
  File getFile();

  /**
   * @return if this request is to follow redirects. Non null values means "override config value".
   */
  Boolean getFollowRedirect();

  /**
   * @return the request timeout. Non zero values means "override config value".
   */
  int getRequestTimeout();

  /**
   * @return the read timeout. Non zero values means "override config value".
   */
  int getReadTimeout();

  /**
   * @return the range header value, or 0 is not set.
   */
  long getRangeOffset();

  /**
   * @return the charset value used when decoding the request's body.
   */
  Charset getCharset();

  /**
   * @return the strategy to compute ChannelPool's keys
   */
  ChannelPoolPartitioning getChannelPoolPartitioning();

  /**
   * @return the NameResolver to be used to resolve hostnams's IP
   */
  NameResolver<InetAddress> getNameResolver();
}
