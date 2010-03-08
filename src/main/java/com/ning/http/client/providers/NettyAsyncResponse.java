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

import com.ning.http.client.Headers;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import com.ning.http.url.Url;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.HttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.util.Collection;
import java.util.List;

/**
 * Wrapper around the {@link com.ning.http.client.Response} API.
 *
 */
public class NettyAsyncResponse implements Response {
    private final Url url;
    private final Collection<HttpResponseBodyPart<HttpResponse>> bodyParts;
    private final HttpResponseHeaders<HttpResponse> headers;
    private final HttpResponseStatus<HttpResponse> status;

    public NettyAsyncResponse(HttpResponseStatus<HttpResponse> status,
                              HttpResponseHeaders<HttpResponse> headers,
                              Collection<HttpResponseBodyPart<HttpResponse>> bodyParts) {

        this.status = status;
        this.headers = headers;
        this.bodyParts = bodyParts;
        url = status.getUrl();
    }

    /* @Override */
    public int getStatusCode() {
        return status.getStatusCode();
    }

    /* @Override */
    public String getStatusText() {
        return status.getStatusText();
    }

    /* @Override */
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
        return contentToString(charset);
    }

    String contentToString(String charset) throws UnsupportedEncodingException {
        StringBuilder b = new StringBuilder();
        for (HttpResponseBodyPart bp: bodyParts){
            b.append(new String(bp.getBodyPartBytes(),charset));
        }
        return b.toString();
    }

    /* @Override */
    public InputStream getResponseBodyAsStream() throws IOException {
        ChannelBuffer buf =  ChannelBuffers.dynamicBuffer();
        for (HttpResponseBodyPart bp: bodyParts){
            // Ugly. TODO
            // (1) We must remove the downcast,
            // (2) we need a CompositeByteArrayInputStream to avoid
            // copying the bytes.
            if (bp.getClass().isAssignableFrom(ResponseBodyPart.class)){
                buf.writeBytes(((ResponseBodyPart)bp).chunk().getContent());
            }
        }
        return new ChannelBufferInputStream(buf); 
    }

    /* @Override */
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
        String response = contentToString(charset);    
        return response.length() <= maxLength ? response : response.substring(0,maxLength);
    }

    /* @Override */
    public Url getUrl() throws MalformedURLException {
        return url;
    }

    /* @Override */
    public String getContentType() {
        return headers.getHeaders().getHeaderValue("Content-Type");
    }

    /* @Override */
    public String getHeader(String name) {
        return headers.getHeaders().getHeaderValue(name);
    }

    /* @Override */
    public List<String> getHeaders(String name) {
        return headers.getHeaders().getHeaderValues(name);
    }

    /* @Override */
    public Headers getHeaders() {
        return headers.getHeaders();
    }

    /* @Override */
    public boolean isRedirected() {
        return (status.getStatusCode() >= 300) && (status.getStatusCode() <= 399);
    }

}
