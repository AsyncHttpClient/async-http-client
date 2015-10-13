/*
 * Copyright (c) 201( AsyncHttpClient Project. All rights reserved.
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
import org.asynchttpclient.Realm.RealmBuilder;
import org.asynchttpclient.proxy.ProxyServer.ProxyServerBuilder;

public final class Dsl {

    // /////////// Client ////////////////
    public static AsyncHttpClient newAsyncHttpClient() {
        return new DefaultAsyncHttpClient();
    }

    public static AsyncHttpClient newAsyncHttpClient(DefaultAsyncHttpClientConfig.Builder configBuilder) {
        return new DefaultAsyncHttpClient(configBuilder.build());
    }
    
    public static AsyncHttpClient newAsyncHttpClient(AsyncHttpClientConfig config) {
        return new DefaultAsyncHttpClient(config);
    }

    // /////////// ProxyServer ////////////////
    public static ProxyServerBuilder newProxyServer(String host, int port) {
        return new ProxyServerBuilder(host, port);
    }

    // /////////// Config ////////////////
    public static DefaultAsyncHttpClientConfig.Builder newConfig() {
        return new DefaultAsyncHttpClientConfig.Builder();
    }

    // /////////// Realm ////////////////
    public static RealmBuilder newRealm(Realm prototype) {
        return new RealmBuilder()//
                .realmName(prototype.getRealmName())//
                .algorithm(prototype.getAlgorithm())//
                .methodName(prototype.getMethodName())//
                .nc(prototype.getNc())//
                .nonce(prototype.getNonce())//
                .password(prototype.getPassword())//
                .principal(prototype.getPrincipal())//
                .charset(prototype.getCharset())//
                .opaque(prototype.getOpaque())//
                .qop(prototype.getQop())//
                .scheme(prototype.getScheme())//
                .uri(prototype.getUri())//
                .usePreemptiveAuth(prototype.isUsePreemptiveAuth())//
                .ntlmDomain(prototype.getNtlmDomain())//
                .ntlmHost(prototype.getNtlmHost())//
                .useAbsoluteURI(prototype.isUseAbsoluteURI())//
                .omitQuery(prototype.isOmitQuery());
    }

    public static RealmBuilder newRealm(AuthScheme scheme, String principal, String password) {
        return new RealmBuilder()//
                .scheme(scheme)//
                .principal(principal)//
                .password(password);
    }

    public static RealmBuilder newBasicAuth(String principal, String password) {
        return newRealm(AuthScheme.BASIC, principal, password);
    }

    public static RealmBuilder newDigestAuth(String principal, String password) {
        return newRealm(AuthScheme.DIGEST, principal, password);
    }

    public static RealmBuilder newNtlmAuth(String principal, String password) {
        return newRealm(AuthScheme.NTLM, principal, password);
    }

    private Dsl() {
    }
}
