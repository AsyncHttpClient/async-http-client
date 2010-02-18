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
package com.ning.http.client;

import com.ning.http.collection.Pair;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class Request {
    private final RequestType type;
    private String url;
    private Headers headers;
    private Collection<Cookie> cookies;
    private byte[] byteData;
    private String stringData;
    private InputStream streamData;
    private EntityWriter entityWriter;
    private Map<String, String> params;
    private List<Part> parts;
    private String virtualHost;
    private long length = -1;

    public static interface EntityWriter {
        public void writeEntity(OutputStream out) throws IOException;
    }

    public Request(RequestType type, String url) {
        this.type = type;
        this.url = url;
    }

    public Request(RequestType type, String url, Headers headers, List<Cookie> cookies) {
        this.type = type;
        this.url = url;
        this.headers = headers;
        this.cookies = cookies;
    }

    public Request(RequestType type, String url, byte[] data) {
        if ((type != RequestType.POST) && (type != RequestType.PUT)) {
            throw new IllegalArgumentException("Illegal request type");
        }
        this.type = type;
        this.url = url;
        this.byteData = data;
    }

    public Request(RequestType type, String url, Headers headers, List<Cookie> cookies, byte[] data) {
        if ((type != RequestType.POST) && (type != RequestType.PUT)) {
            throw new IllegalArgumentException("Illegal request type");
        }
        this.type = type;
        this.url = url;
        this.headers = headers;
        this.cookies = cookies;
        this.byteData = data;
    }

    public Request(RequestType type, String url, String data) {
        if ((type != RequestType.POST) && (type != RequestType.PUT)) {
            throw new IllegalArgumentException("Illegal request type");
        }
        this.type = type;
        this.url = url;
        this.stringData = data;
    }

    public Request(RequestType type, String url, Headers headers, List<Cookie> cookies, String data) {
        if ((type != RequestType.POST) && (type != RequestType.PUT)) {
            throw new IllegalArgumentException("Illegal request type");
        }
        this.type = type;
        this.url = url;
        this.headers = headers;
        this.cookies = cookies;
        this.stringData = data;
    }

    public Request(RequestType type, String url, InputStream data) {
        this(type, url, null, null, data, -1);
    }

    public Request(RequestType type, String url, Headers headers, List<Cookie> cookies, InputStream data) {
        this(type, url, headers, cookies, data, -1);
    }

    public Request(RequestType type, String url, Headers headers, List<Cookie> cookies, InputStream data, long length) {
        this(type, url, headers, cookies, data, null, length);
    }

    public Request(RequestType type, String url, Headers headers, List<Cookie> cookies, EntityWriter entityWriter, long length) {
        this(type, url, headers, cookies, null, entityWriter, length);
    }

    private Request(RequestType type, String url, Headers headers, List<Cookie> cookies, InputStream data, EntityWriter entityWriter, long length) {
        if ((type != RequestType.POST) && (type != RequestType.PUT)) {
            throw new IllegalArgumentException("Illegal request type");
        }

        if (data != null && entityWriter != null) {
            throw new IllegalArgumentException("Can't specify both InputStream data and an EntityWriter");
        }

        this.type = type;
        this.url = url;
        this.headers = headers;
        this.cookies = cookies;
        this.streamData = data;
        this.entityWriter = entityWriter;
        if (length >= 0) {
            this.length = length;
        } else {
            this.length = getContentLength(headers);
        }
    }

    public Request(RequestType type, String url, Map<String, String> params) {
        if (type != RequestType.POST) {
            throw new IllegalArgumentException("Illegal request type");
        }
        this.type = type;
        this.url = url;
        this.params = params;
    }

    public Request(RequestType type, String url, Headers headers, List<Cookie> cookies, Map<String, String> params) {
        if (type != RequestType.POST) {
            throw new IllegalArgumentException("Illegal request type");
        }
        this.type = type;
        this.url = url;
        this.headers = headers;
        this.cookies = cookies;
        this.params = params;
    }

    public Request(RequestType type, String url, List<Part> parts) {
        if (type != RequestType.POST) {
            throw new IllegalArgumentException("Illegal request type");
        }
        this.type = type;
        this.url = url;
        this.parts = parts;
    }

    public Request(RequestType type, String url, Headers headers, List<Cookie> cookies, List<Part> parts) {
        if (type != RequestType.POST) {
            throw new IllegalArgumentException("Illegal request type");
        }
        this.type = type;
        this.url = url;
        this.headers = headers;
        this.cookies = cookies;
        this.parts = parts;
    }

    public RequestType getType() {
        return type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Headers getHeaders() {
        return headers;
    }

    public void setHeaders(Headers headers) {
        this.headers = headers;
        // content length applies when streamData is not null
        if (streamData != null && length == -1) {
            this.length = getContentLength(headers);
        }
    }

    public Collection<Cookie> getCookies() {
        return cookies;
    }

    public void setCookies(Collection<Cookie> cookies) {
        this.cookies = cookies;
    }

    public byte[] getByteData() {
        return byteData;
    }

    public String getStringData() {
        return stringData;
    }

    public InputStream getStreamData() {
        return streamData;
    }

    public Map<String, String> getParams() {
        return params;
    }

    public List<Part> getParts() {
        return parts;
    }

    public String getVirtualHost() {
        return virtualHost;
    }

    public static final long getContentLength(Headers headers) {
        if (headers != null) {
            String contentLength = headers.getHeaderValue("Content-Length");
            if (contentLength != null) {
                try {
                    return Long.parseLong(contentLength);
                }
                catch (NumberFormatException e) {
                }
            }
        }
        return -1;
    }

    public long getLength() {
        return length;
    }

    public void setVirtualHost(String virtualHost) {
        this.virtualHost = virtualHost;
    }

    public EntityWriter getEntityWriter() {
        return entityWriter;
    }

    public void setEntityWriter(EntityWriter entityWriter) {
        this.entityWriter = entityWriter;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(url);

        sb.append("\t");
        sb.append(type);

        if (headers != null) {
            for (Pair<String, String> header : headers) {
                sb.append("\t");
                sb.append(header.getFirst());
                sb.append(":");
                sb.append(header.getSecond());
            }
        }

        return sb.toString();
    }
}
