/*
 * Copyright 2010 Ning, Inc.
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

import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.multipart.Part;
import org.asynchttpclient.util.QueryComputer;

/**
 * Builder for a {@link Request}.
 * Warning: mutable and not thread-safe! Beware that it holds a reference on the Request instance it builds,
 * so modifying the builder will modify the request even after it has been built.
 */
public class RequestBuilder extends RequestBuilderBase<RequestBuilder> {

    public RequestBuilder() {
        super(RequestBuilder.class, "GET", false);
    }

    public RequestBuilder(String method) {
        super(RequestBuilder.class, method, false);
    }

    public RequestBuilder(String method, boolean useRawUrl) {
        super(RequestBuilder.class, method, useRawUrl);
    }

    public RequestBuilder(String method, QueryComputer queryComputer) {
        super(RequestBuilder.class, method, queryComputer);
    }

    public RequestBuilder(Request prototype) {
        super(RequestBuilder.class, prototype);
    }

    public RequestBuilder(Request prototype, QueryComputer queryComputer) {
        super(RequestBuilder.class, prototype, queryComputer);
    }
    
    // Note: For now we keep the delegates in place even though they are not needed
    //       since otherwise Clojure (and maybe other languages) won't be able to
    //       access these methods - see Clojure tickets 126 and 259

    @Override
    public RequestBuilder addBodyPart(Part part) {
        return super.addBodyPart(part);
    }

    @Override
    public RequestBuilder addCookie(Cookie cookie) {
        return super.addCookie(cookie);
    }

    @Override
    public RequestBuilder addHeader(String name, String value) {
        return super.addHeader(name, value);
    }

    @Override
    public RequestBuilder addFormParam(String key, String value) {
        return super.addFormParam(key, value);
    }

    @Override
    public RequestBuilder addQueryParam(String name, String value) {
        return super.addQueryParam(name, value);
    }

    @Override
    public RequestBuilder addQueryParams(List<Param> queryParams) {
        return super.addQueryParams(queryParams);
    }

    @Override
    public RequestBuilder setQueryParams(List<Param> params) {
        return super.setQueryParams(params);
    }

    @Override
    public RequestBuilder setQueryParams(Map<String, List<String>> params) {
        return super.setQueryParams(params);
    }

    @Override
    public Request build() {
        return super.build();
    }

    @Override
    public RequestBuilder setBody(byte[] data) {
        return super.setBody(data);
    }

    @Override
    public RequestBuilder setBody(InputStream stream) {
        return super.setBody(stream);
    }

    @Override
    public RequestBuilder setBody(String data) {
        return super.setBody(data);
    }

    @Override
    public RequestBuilder setHeader(String name, String value) {
        return super.setHeader(name, value);
    }

    @Override
    public RequestBuilder setHeaders(FluentCaseInsensitiveStringsMap headers) {
        return super.setHeaders(headers);
    }

    @Override
    public RequestBuilder setHeaders(Map<String, Collection<String>> headers) {
        return super.setHeaders(headers);
    }

    @Override
    public RequestBuilder setFormParams(List<Param> params) {
        return super.setFormParams(params);
    }

    @Override
    public RequestBuilder setFormParams(Map<String, List<String>> params) {
        return super.setFormParams(params);
    }

    @Override
    public RequestBuilder setMethod(String method) {
        return super.setMethod(method);
    }

    @Override
    public RequestBuilder setUrl(String url) {
        return super.setUrl(url);
    }

    @Override
    public RequestBuilder setProxyServer(ProxyServer proxyServer) {
        return super.setProxyServer(proxyServer);
    }

    @Override
    public RequestBuilder setVirtualHost(String virtualHost) {
        return super.setVirtualHost(virtualHost);
    }

    @Override
    public RequestBuilder setFollowRedirect(boolean followRedirect) {
        return super.setFollowRedirect(followRedirect);
    }

    @Override
    public RequestBuilder addOrReplaceCookie(Cookie c) {
        return super.addOrReplaceCookie(c);
    }
}
