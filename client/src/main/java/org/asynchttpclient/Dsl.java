/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.proxy.ProxyServer;

import static org.asynchttpclient.util.HttpConstants.Methods.*;

/**
 * Domain Specific Language (DSL) for creating async-http-client instances and builders.
 * <p>
 * This class provides static factory methods for conveniently creating clients, requests,
 * realms, and proxy servers. It serves as the primary entry point for most applications
 * using async-http-client.
 * </p>
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create a client with default configuration
 * AsyncHttpClient client = Dsl.asyncHttpClient();
 *
 * // Create a client with custom configuration
 * AsyncHttpClient client = Dsl.asyncHttpClient(
 *     Dsl.config()
 *         .setConnectTimeout(5000)
 *         .setRequestTimeout(10000)
 * );
 *
 * // Build a request
 * Request request = Dsl.get("http://example.com")
 *     .setHeader("Accept", "application/json")
 *     .build();
 *
 * // Create a proxy
 * ProxyServer proxy = Dsl.proxyServer("proxy.example.com", 8080)
 *     .setProxyType(ProxyType.HTTP)
 *     .build();
 *
 * // Create authentication realm
 * Realm realm = Dsl.basicAuthRealm("username", "password")
 *     .setUsePreemptiveAuth(true)
 *     .build();
 * }</pre>
 *
 * @see AsyncHttpClient
 * @see RequestBuilder
 * @see Realm
 * @see ProxyServer
 */
public final class Dsl {

  private Dsl() {
    // Utility class, prevent instantiation
  }

  // /////////// Client ////////////////

  /**
   * Creates an {@link AsyncHttpClient} with default configuration.
   *
   * @return a new AsyncHttpClient instance
   */
  public static AsyncHttpClient asyncHttpClient() {
    return new DefaultAsyncHttpClient();
  }

  /**
   * Creates an {@link AsyncHttpClient} with custom configuration.
   *
   * @param configBuilder the configuration builder
   * @return a new AsyncHttpClient instance with the specified configuration
   */
  public static AsyncHttpClient asyncHttpClient(DefaultAsyncHttpClientConfig.Builder configBuilder) {
    return new DefaultAsyncHttpClient(configBuilder.build());
  }

  /**
   * Creates an {@link AsyncHttpClient} with custom configuration.
   *
   * @param config the client configuration
   * @return a new AsyncHttpClient instance with the specified configuration
   */
  public static AsyncHttpClient asyncHttpClient(AsyncHttpClientConfig config) {
    return new DefaultAsyncHttpClient(config);
  }

  // /////////// Request ////////////////

  /**
   * Creates a GET request builder for the specified URL.
   *
   * @param url the target URL
   * @return a RequestBuilder configured for a GET request
   */
  public static RequestBuilder get(String url) {
    return request(GET, url);
  }

  /**
   * Creates a PUT request builder for the specified URL.
   *
   * @param url the target URL
   * @return a RequestBuilder configured for a PUT request
   */
  public static RequestBuilder put(String url) {
    return request(PUT, url);
  }

  /**
   * Creates a POST request builder for the specified URL.
   *
   * @param url the target URL
   * @return a RequestBuilder configured for a POST request
   */
  public static RequestBuilder post(String url) {
    return request(POST, url);
  }

  /**
   * Creates a DELETE request builder for the specified URL.
   *
   * @param url the target URL
   * @return a RequestBuilder configured for a DELETE request
   */
  public static RequestBuilder delete(String url) {
    return request(DELETE, url);
  }

  /**
   * Creates a HEAD request builder for the specified URL.
   *
   * @param url the target URL
   * @return a RequestBuilder configured for a HEAD request
   */
  public static RequestBuilder head(String url) {
    return request(HEAD, url);
  }

  /**
   * Creates an OPTIONS request builder for the specified URL.
   *
   * @param url the target URL
   * @return a RequestBuilder configured for an OPTIONS request
   */
  public static RequestBuilder options(String url) {
    return request(OPTIONS, url);
  }

  /**
   * Creates a PATCH request builder for the specified URL.
   *
   * @param url the target URL
   * @return a RequestBuilder configured for a PATCH request
   */
  public static RequestBuilder patch(String url) {
    return request(PATCH, url);
  }

  /**
   * Creates a TRACE request builder for the specified URL.
   *
   * @param url the target URL
   * @return a RequestBuilder configured for a TRACE request
   */
  public static RequestBuilder trace(String url) {
    return request(TRACE, url);
  }

  /**
   * Creates a request builder for the specified HTTP method and URL.
   *
   * @param method the HTTP method (e.g., "GET", "POST")
   * @param url the target URL
   * @return a RequestBuilder configured for the specified method and URL
   */
  public static RequestBuilder request(String method, String url) {
    return new RequestBuilder(method).setUrl(url);
  }

  // /////////// ProxyServer ////////////////

  /**
   * Creates a proxy server builder.
   *
   * @param host the proxy server hostname
   * @param port the proxy server port
   * @return a ProxyServer.Builder for configuring the proxy
   */
  public static ProxyServer.Builder proxyServer(String host, int port) {
    return new ProxyServer.Builder(host, port);
  }

  // /////////// Config ////////////////

  /**
   * Creates a configuration builder for customizing client behavior.
   *
   * @return a new DefaultAsyncHttpClientConfig.Builder
   */
  public static DefaultAsyncHttpClientConfig.Builder config() {
    return new DefaultAsyncHttpClientConfig.Builder();
  }

  // /////////// Realm ////////////////

  /**
   * Creates a realm builder based on an existing realm prototype.
   *
   * @param prototype the realm to use as a template
   * @return a Realm.Builder initialized with the prototype's values
   */
  public static Realm.Builder realm(Realm prototype) {
    return new Realm.Builder(prototype.getPrincipal(), prototype.getPassword())
            .setRealmName(prototype.getRealmName())
            .setAlgorithm(prototype.getAlgorithm())
            .setNc(prototype.getNc())
            .setNonce(prototype.getNonce())
            .setCharset(prototype.getCharset())
            .setOpaque(prototype.getOpaque())
            .setQop(prototype.getQop())
            .setScheme(prototype.getScheme())
            .setUri(prototype.getUri())
            .setUsePreemptiveAuth(prototype.isUsePreemptiveAuth())
            .setNtlmDomain(prototype.getNtlmDomain())
            .setNtlmHost(prototype.getNtlmHost())
            .setUseAbsoluteURI(prototype.isUseAbsoluteURI())
            .setOmitQuery(prototype.isOmitQuery())
            .setServicePrincipalName(prototype.getServicePrincipalName())
            .setUseCanonicalHostname(prototype.isUseCanonicalHostname())
            .setCustomLoginConfig(prototype.getCustomLoginConfig())
            .setLoginContextName(prototype.getLoginContextName());
  }

  /**
   * Creates a realm builder with the specified authentication scheme.
   *
   * @param scheme the authentication scheme to use
   * @param principal the username
   * @param password the password
   * @return a Realm.Builder configured with the specified credentials and scheme
   */
  public static Realm.Builder realm(AuthScheme scheme, String principal, String password) {
    return new Realm.Builder(principal, password)
            .setScheme(scheme);
  }

  /**
   * Creates a realm builder for HTTP Basic authentication.
   *
   * @param principal the username
   * @param password the password
   * @return a Realm.Builder configured for Basic authentication
   */
  public static Realm.Builder basicAuthRealm(String principal, String password) {
    return realm(AuthScheme.BASIC, principal, password);
  }

  /**
   * Creates a realm builder for HTTP Digest authentication.
   *
   * @param principal the username
   * @param password the password
   * @return a Realm.Builder configured for Digest authentication
   */
  public static Realm.Builder digestAuthRealm(String principal, String password) {
    return realm(AuthScheme.DIGEST, principal, password);
  }

  /**
   * Creates a realm builder for NTLM authentication.
   *
   * @param principal the username
   * @param password the password
   * @return a Realm.Builder configured for NTLM authentication
   */
  public static Realm.Builder ntlmAuthRealm(String principal, String password) {
    return realm(AuthScheme.NTLM, principal, password);
  }
}
