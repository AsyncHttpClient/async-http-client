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
 *
 */
package com.ning.http.client.webdav;

import com.ning.http.client.Cookie;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.Response;
import org.w3c.dom.Document;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;

/**
 * Customized {@link Response} which add support for getting the response's body as an XML document (@link WebDavResponse#getBodyAsXML}
 */
public class WebDavResponse implements Response{

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

    public InputStream getResponseBodyAsStream() throws IOException {
        return response.getResponseBodyAsStream();
    }

    public String getResponseBodyExcerpt(int maxLength) throws IOException {
        return response.getResponseBodyExcerpt(maxLength);
    }

    public String getResponseBody() throws IOException {
        return response.getResponseBody();
    }

    public URI getUri() throws MalformedURLException {
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

    public Document getBodyAsXML(){
        return document;
    }
}
