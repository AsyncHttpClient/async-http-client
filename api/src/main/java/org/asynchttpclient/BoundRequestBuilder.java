/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.multipart.Part;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class BoundRequestBuilder extends RequestBuilderBase<BoundRequestBuilder> {

    private final AsyncHttpClient client;

    public BoundRequestBuilder(AsyncHttpClient client, String method, boolean isDisableUrlEncoding) {
        super(BoundRequestBuilder.class, method, isDisableUrlEncoding);
        this.client = client;
    }

    public BoundRequestBuilder(AsyncHttpClient client, Request prototype) {
        super(BoundRequestBuilder.class, prototype);
        this.client = client;
    }

    public <T> ListenableFuture<T> execute(AsyncHandler<T> handler) throws IOException {
        return client.executeRequest(build(), handler);
    }

    public ListenableFuture<Response> execute() throws IOException {
        return client.executeRequest(build(), new AsyncCompletionHandlerBase());
    }

    // Note: For now we keep the delegates in place even though they are not
    // needed
    // since otherwise Clojure (and maybe other languages) won't be able to
    // access these methods - see Clojure tickets 126 and 259

    @Override
    public BoundRequestBuilder addBodyPart(Part part) {
        return super.addBodyPart(part);
    }

    @Override
    public BoundRequestBuilder addCookie(Cookie cookie) {
        return super.addCookie(cookie);
    }

    @Override
    public BoundRequestBuilder addHeader(String name, String value) {
        return super.addHeader(name, value);
    }

    @Override
    public BoundRequestBuilder addFormParam(String key, String value) {
        return super.addFormParam(key, value);
    }

    @Override
    public BoundRequestBuilder addQueryParam(String name, String value) {
        return super.addQueryParam(name, value);
    }

    @Override
    public Request build() {
        return super.build();
    }

    @Override
    public BoundRequestBuilder setBody(byte[] data) {
        return super.setBody(data);
    }

    @Override
    public BoundRequestBuilder setBody(InputStream stream) {
        return super.setBody(stream);
    }

    @Override
    public BoundRequestBuilder setBody(String data) {
        return super.setBody(data);
    }

    @Override
    public BoundRequestBuilder setHeader(String name, String value) {
        return super.setHeader(name, value);
    }

    @Override
    public BoundRequestBuilder setHeaders(FluentCaseInsensitiveStringsMap headers) {
        return super.setHeaders(headers);
    }

    @Override
    public BoundRequestBuilder setHeaders(Map<String, Collection<String>> headers) {
        return super.setHeaders(headers);
    }

    @Override
    public BoundRequestBuilder setFormParams(Map<String, List<String>> params) {
        return super.setFormParams(params);
    }

    @Override
    public BoundRequestBuilder setFormParams(List<Param> params) {
        return super.setFormParams(params);
    }

    @Override
    public BoundRequestBuilder setUrl(String url) {
        return super.setUrl(url);
    }

    @Override
    public BoundRequestBuilder setVirtualHost(String virtualHost) {
        return super.setVirtualHost(virtualHost);
    }

    public BoundRequestBuilder setSignatureCalculator(SignatureCalculator signatureCalculator) {
        return super.setSignatureCalculator(signatureCalculator);
    }
}
