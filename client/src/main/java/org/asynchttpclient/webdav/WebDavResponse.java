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
import org.jetbrains.annotations.Nullable;
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
    private final @Nullable Document document;

    WebDavResponse(Response response, @Nullable Document document) {
        this.response = response;
        this.document = document;
    }

    @Override
    public int getStatusCode() {
        return response.getStatusCode();
    }

    @Override
    public String getStatusText() {
        return response.getStatusText();
    }

    @Override
    public byte[] getResponseBodyAsBytes() {
        return response.getResponseBodyAsBytes();
    }

    @Override
    public ByteBuffer getResponseBodyAsByteBuffer() {
        return response.getResponseBodyAsByteBuffer();
    }

    @Override
    public InputStream getResponseBodyAsStream() {
        return response.getResponseBodyAsStream();
    }

    @Override
    public String getResponseBody() {
        return response.getResponseBody();
    }

    @Override
    public String getResponseBody(Charset charset) {
        return response.getResponseBody(charset);
    }

    @Override
    public Uri getUri() {
        return response.getUri();
    }

    @Override
    public String getContentType() {
        return response.getContentType();
    }

    @Override
    public String getHeader(CharSequence name) {
        return response.getHeader(name);
    }

    @Override
    public List<String> getHeaders(CharSequence name) {
        return response.getHeaders(name);
    }

    @Override
    public HttpHeaders getHeaders() {
        return response.getHeaders();
    }

    @Override
    public boolean isRedirected() {
        return response.isRedirected();
    }

    @Override
    public List<Cookie> getCookies() {
        return response.getCookies();
    }

    @Override
    public boolean hasResponseStatus() {
        return response.hasResponseStatus();
    }

    @Override
    public boolean hasResponseHeaders() {
        return response.hasResponseHeaders();
    }

    @Override
    public boolean hasResponseBody() {
        return response.hasResponseBody();
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return response.getRemoteAddress();
    }

    @Override
    public SocketAddress getLocalAddress() {
        return response.getLocalAddress();
    }

    public @Nullable Document getBodyAsXML() {
        return document;
    }
}
