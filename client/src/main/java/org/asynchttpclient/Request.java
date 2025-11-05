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
 * Represents an immutable HTTP request.
 * <p>
 * Request instances are built using {@link RequestBuilder} and contain all the information
 * needed to execute an HTTP request including the URL, HTTP method, headers, body, and
 * various configuration options.
 * </p>
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * Request request = new RequestBuilder()
 *     .setUrl("https://api.example.com/users")
 *     .setMethod("POST")
 *     .setHeader("Content-Type", "application/json")
 *     .setBody("{\"name\":\"John\"}")
 *     .setRealm(new Realm.Builder("username", "password")
 *         .setScheme(Realm.AuthScheme.BASIC)
 *         .build())
 *     .build();
 *
 * AsyncHttpClient client = Dsl.asyncHttpClient();
 * Future<Response> future = client.executeRequest(request);
 * }</pre>
 */
public interface Request {

  /**
   * Returns the HTTP method of this request.
   *
   * @return the HTTP method (e.g., "GET", "POST", "PUT", "DELETE")
   */
  String getMethod();

  /**
   * Returns the URI of this request.
   *
   * @return the parsed {@link Uri} object
   */
  Uri getUri();

  /**
   * Returns the URL of this request as a string.
   *
   * @return the URL string representation
   */
  String getUrl();

  /**
   * Returns the specific InetAddress to use for this request, bypassing DNS resolution.
   *
   * @return the InetAddress to connect to, or null to use normal DNS resolution
   */
  InetAddress getAddress();

  /**
   * Returns the local address to bind from when making this request.
   *
   * @return the local InetAddress to bind from, or null for default behavior
   */
  InetAddress getLocalAddress();

  /**
   * Returns the HTTP headers for this request.
   *
   * @return the HTTP headers collection
   */
  HttpHeaders getHeaders();

  /**
   * Returns the cookies to be sent with this request.
   *
   * @return the list of cookies, or an empty list if none
   */
  List<Cookie> getCookies();

  /**
   * Returns the request body as a byte array, if set.
   *
   * @return the request body byte array, or null if the body was not set this way
   */
  byte[] getByteData();

  /**
   * Returns the request body as a composite list of byte arrays, if set.
   *
   * @return the list of byte arrays, or null if the body was not set this way
   */
  List<byte[]> getCompositeByteData();

  /**
   * Returns the request body as a string, if set.
   *
   * @return the request body string, or null if the body was not set this way
   */
  String getStringData();

  /**
   * Returns the request body as a ByteBuffer, if set.
   *
   * @return the request body ByteBuffer, or null if the body was not set this way
   */
  ByteBuffer getByteBufferData();

  /**
   * Returns the request body as an InputStream, if set.
   *
   * @return the request body InputStream, or null if the body was not set this way
   */
  InputStream getStreamData();

  /**
   * Returns the request body generator, if set.
   *
   * @return the BodyGenerator, or null if the body was not set this way
   */
  BodyGenerator getBodyGenerator();

  /**
   * Returns the form parameters for this request.
   *
   * @return the list of form parameters, or an empty list if none
   */
  List<Param> getFormParams();

  /**
   * Returns the multipart body parts for this request.
   *
   * @return the list of multipart parts, or an empty list if none
   */
  List<Part> getBodyParts();

  /**
   * Returns the virtual host header value for this request.
   *
   * @return the virtual host, or null if not set
   */
  String getVirtualHost();

  /**
   * Returns the query parameters extracted from the URL.
   *
   * @return the list of query parameters, or an empty list if none
   */
  List<Param> getQueryParams();

  /**
   * Returns the proxy server to use for this request.
   * If set, this overrides the proxy server defined in the client configuration.
   *
   * @return the proxy server, or null to use the client's default configuration
   */
  ProxyServer getProxyServer();

  /**
   * Returns the authentication realm for this request.
   * If set, this overrides the realm defined in the client configuration.
   *
   * @return the authentication realm, or null to use the client's default configuration
   */
  Realm getRealm();

  /**
   * Returns the file to be uploaded as the request body.
   *
   * @return the file to upload, or null if not set
   */
  File getFile();

  /**
   * Returns whether this request should follow redirects.
   *
   * @return true to follow redirects, false to not follow them, or null to use the client's default configuration
   */
  Boolean getFollowRedirect();

  /**
   * Returns the request timeout in milliseconds.
   *
   * @return the request timeout in milliseconds, or 0 to use the client's default configuration
   */
  int getRequestTimeout();

  /**
   * Returns the read timeout in milliseconds.
   *
   * @return the read timeout in milliseconds, or 0 to use the client's default configuration
   */
  int getReadTimeout();

  /**
   * Returns the byte offset for HTTP range requests.
   *
   * @return the range offset in bytes, or 0 if not set
   */
  long getRangeOffset();

  /**
   * Returns the charset used for encoding/decoding the request body.
   *
   * @return the charset, or null to use the default charset
   */
  Charset getCharset();

  /**
   * Returns the channel pool partitioning strategy for this request.
   *
   * @return the channel pool partitioning strategy
   */
  ChannelPoolPartitioning getChannelPoolPartitioning();

  /**
   * Returns the name resolver to use for hostname resolution.
   *
   * @return the name resolver, or null to use the client's default resolver
   */
  NameResolver<InetAddress> getNameResolver();

  /**
   * Creates a new {@link RequestBuilder} initialized with this request's values.
   * This allows modification of an existing request.
   *
   * @return a new RequestBuilder based on this request
   */
  @SuppressWarnings("deprecation")
  default RequestBuilder toBuilder() {
    return new RequestBuilder(this);
  }
}
