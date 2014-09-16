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
package com.ning.http.client.webdav;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

import org.w3c.dom.Document;

import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.Response;
import com.ning.http.client.cookie.Cookie;
import com.ning.http.client.uri.Uri;

/**
 * Customized {@link Response} which add support for getting the response's body as an XML document (@link WebDavResponse#getBodyAsXML}
 */
public class WebDavResponse implements Response {

    private final Response response;
    private final Document document;

    public WebDavResponse(Response response, Document document) {
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
    public byte[] getResponseBodyAsBytes() throws IOException {
        return response.getResponseBodyAsBytes();
    }

    public ByteBuffer getResponseBodyAsByteBuffer() throws IOException {
        return response.getResponseBodyAsByteBuffer();
    }

    public InputStream getResponseBodyAsStream() throws IOException {
        return response.getResponseBodyAsStream();
    }

    public String getResponseBodyExcerpt(int maxLength) throws IOException {
        return response.getResponseBodyExcerpt(maxLength);
    }

    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {
        return response.getResponseBodyExcerpt(maxLength, charset);
    }

    public String getResponseBody() throws IOException {
        return response.getResponseBody();
    }

    public String getResponseBody(String charset) throws IOException {
        return response.getResponseBody(charset);
    }

    public Uri getUri() {
        return response.getUri();
    }

    public String getContentType() {
        return response.getContentType();
    }

    public String getHeader(String name) {
        return response.getHeader(name);
    }

    public List<String> getHeaders(String name) {
        return response.getHeaders(name);
    }

    public FluentCaseInsensitiveStringsMap getHeaders() {
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

    public Document getBodyAsXML() {
        return document;
    }
}
