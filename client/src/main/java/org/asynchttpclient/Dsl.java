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

public final class Dsl {

  private Dsl() {
  }

  // /////////// Client ////////////////
  public static AsyncHttpClient asyncHttpClient() {
    return new DefaultAsyncHttpClient();
  }

  public static AsyncHttpClient asyncHttpClient(DefaultAsyncHttpClientConfig.Builder configBuilder) {
    return new DefaultAsyncHttpClient(configBuilder.build());
  }

  public static AsyncHttpClient asyncHttpClient(AsyncHttpClientConfig config) {
    return new DefaultAsyncHttpClient(config);
  }

  // /////////// Request ////////////////
  public static RequestBuilder get(String url) {
    return request(GET, url);
  }

  public static RequestBuilder put(String url) {
    return request(PUT, url);
  }

  public static RequestBuilder post(String url) {
    return request(POST, url);
  }

  public static RequestBuilder delete(String url) {
    return request(DELETE, url);
  }

  public static RequestBuilder head(String url) {
    return request(HEAD, url);
  }

  public static RequestBuilder options(String url) {
    return request(OPTIONS, url);
  }

  public static RequestBuilder patch(String url) {
    return request(PATCH, url);
  }

  public static RequestBuilder trace(String url) {
    return request(TRACE, url);
  }

  public static RequestBuilder request(String method, String url) {
    return new RequestBuilder(method).setUrl(url);
  }

  // /////////// ProxyServer ////////////////
  public static ProxyServer.Builder proxyServer(String host, int port) {
    return new ProxyServer.Builder(host, port);
  }

  // /////////// Config ////////////////
  public static DefaultAsyncHttpClientConfig.Builder config() {
    return new DefaultAsyncHttpClientConfig.Builder();
  }

  // /////////// Realm ////////////////
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
            .setOmitQuery(prototype.isOmitQuery());
  }

  public static Realm.Builder realm(AuthScheme scheme, String principal, String password) {
    return new Realm.Builder(principal, password)//
            .setScheme(scheme);
  }

  public static Realm.Builder basicAuthRealm(String principal, String password) {
    return realm(AuthScheme.BASIC, principal, password);
  }

  public static Realm.Builder digestAuthRealm(String principal, String password) {
    return realm(AuthScheme.DIGEST, principal, password);
  }

  public static Realm.Builder ntlmAuthRealm(String principal, String password) {
    return realm(AuthScheme.NTLM, principal, password);
  }
}
