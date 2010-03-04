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
package com.ning.http.client.providers;

import com.ning.http.client.FutureImpl;
import com.ning.http.client.Headers;
import com.ning.http.client.Response;
import com.ning.http.url.Url;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.util.List;

/**
 * Wrapper around the {@link com.ning.http.client.Response} API.
 *
 * @param <V>
 */
public class NettyAsyncResponse<V> implements Response {
    private final Url url;

    private HttpResponse r;
    private FutureImpl<V> rf;
    @SuppressWarnings("unused")
    private V content;
    private HttpChunkTrailer trailingHeaders;
    private ChannelBuffer buffer;

    public NettyAsyncResponse(Url url) {
        this.url = url;
    }

    void setTrailingHeaders(HttpChunkTrailer t) {
        trailingHeaders = t;
    }

    @Override
    public int getStatusCode() {
        return r.getStatus().getCode();
    }

    @Override
    public String getStatusText() {
        return r.getStatus().getReasonPhrase();
    }

    void setBuffer(ChannelBuffer chunkedOutputBuffer) {
        this.buffer = chunkedOutputBuffer;
    }

    ChannelBuffer getBuffer(){
        return buffer;
    }

    @Override
    public InputStream getResponseBodyAsStream() throws IOException {
        if (!r.isChunked()) {
            return new ChannelBufferInputStream(r.getContent());
        } else {
            if (buffer == null) {
                throw new NullPointerException("buffer is null");
            }
            return new ChannelBufferInputStream(buffer);
        }
    }

    public FutureImpl<V> getFuture() {
        return rf;
    }

    void setFuture(FutureImpl<V> rf) {
        this.rf = rf;
    }

    void setResponse(HttpResponse r) {
        this.r = r;
    }

    void setContent(V content) {
        this.content = content;
    }

    @Override
    public String getResponseBody() throws IOException {
        String contentType = getContentType();
        String charset = "UTF-8";
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                if (part.startsWith("charset=")) {
                    charset = part.substring("charset=".length());
                }
            }
        }
        InputStream responseInput = getResponseBodyAsStream();
        return contentToString(charset);
    }

    String contentToString(String charset) throws UnsupportedEncodingException {
        if (!r.isChunked()) {
            return new String(r.getContent().array(),charset);
        } else {
            if (buffer == null) {
                throw new NullPointerException("buffer is null");
            }
            return new String(buffer.array(),charset);
        }
    }

    @Override
    public String getResponseBodyExcerpt(int maxLength) throws IOException {
        String contentType = getContentType();
        String charset = "UTF-8";
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                if (part.startsWith("charset=")) {
                    charset = part.substring("charset=".length());
                }
            }
        }
        InputStream responseInput = getResponseBodyAsStream();
        String response = contentToString(charset);
    
        return response.length() <= maxLength ? response : response.substring(0,maxLength);
    }

    @Override
    public Url getUrl() throws MalformedURLException {
        return url;
    }

    @Override
    public String getContentType() {
        return r.getHeader(HttpHeaders.Names.CONTENT_TYPE);
    }

    @Override
    public String getHeader(String name) {
        String s = r.getHeader(name);
        if (s == null && trailingHeaders != null) {
            return trailingHeaders.getHeader(name);
        }
        return s;
    }

    @Override
    public List<String> getHeaders(String name) {
        List<String> s = r.getHeaders(name);
        if ((s == null || s.size() == 0) && trailingHeaders != null) {
            return trailingHeaders.getHeaders(name);
        } else {
            return s;
        }
    }

    @Override
    public Headers getHeaders() {
        Headers h = new Headers();
        for (String s : r.getHeaderNames()) {
            h.add(s, r.getHeader(s));
        }

        if (trailingHeaders != null && trailingHeaders.getHeaderNames().size() > 0) {
            for (final String s : trailingHeaders.getHeaderNames()) {
                h.add(s, r.getHeader(s));
            }
        }

        return Headers.unmodifiableHeaders(h);
    }

    @Override
    public boolean isRedirected() {
        return (r.getStatus().getCode() >= 300) && (r.getStatus().getCode() <= 399);
    }

}
