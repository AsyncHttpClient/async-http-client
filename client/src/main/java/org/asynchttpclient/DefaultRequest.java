/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.resolver.NameResolver;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.request.body.generator.BodyGenerator;
import org.asynchttpclient.request.body.multipart.Part;
import org.asynchttpclient.uri.Uri;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

public class DefaultRequest implements Request {

    public final @Nullable ProxyServer proxyServer;
    private final String method;
    private final Uri uri;
    private final @Nullable InetAddress address;
    private final @Nullable InetAddress localAddress;
    private final HttpHeaders headers;
    private final List<Cookie> cookies;
    private final byte @Nullable [] byteData;
    private final @Nullable List<byte[]> compositeByteData;
    private final @Nullable String stringData;
    private final @Nullable ByteBuffer byteBufferData;
    private final @Nullable ByteBuf byteBufData;
    private final @Nullable InputStream streamData;
    private final @Nullable BodyGenerator bodyGenerator;
    private final List<Param> formParams;
    private final List<Part> bodyParts;
    private final @Nullable String virtualHost;
    private final @Nullable Realm realm;
    private final @Nullable File file;
    private final @Nullable Boolean followRedirect;
    private final Duration requestTimeout;
    private final Duration readTimeout;
    private final long rangeOffset;
    private final @Nullable Charset charset;
    private final ChannelPoolPartitioning channelPoolPartitioning;
    private final NameResolver<InetAddress> nameResolver;

    // lazily loaded
    private @Nullable List<Param> queryParams;

    public DefaultRequest(String method,
                          Uri uri,
                          @Nullable InetAddress address,
                          @Nullable InetAddress localAddress,
                          HttpHeaders headers,
                          List<Cookie> cookies,
                          byte @Nullable [] byteData,
                          @Nullable List<byte[]> compositeByteData,
                          @Nullable String stringData,
                          @Nullable ByteBuffer byteBufferData,
                          @Nullable ByteBuf byteBufData,
                          @Nullable InputStream streamData,
                          @Nullable BodyGenerator bodyGenerator,
                          List<Param> formParams,
                          List<Part> bodyParts,
                          @Nullable String virtualHost,
                          @Nullable ProxyServer proxyServer,
                          @Nullable Realm realm,
                          @Nullable File file,
                          @Nullable Boolean followRedirect,
                          @Nullable Duration requestTimeout,
                          @Nullable Duration readTimeout,
                          long rangeOffset,
                          @Nullable Charset charset,
                          ChannelPoolPartitioning channelPoolPartitioning,
                          NameResolver<InetAddress> nameResolver) {
        this.method = method;
        this.uri = uri;
        this.address = address;
        this.localAddress = localAddress;
        this.headers = headers;
        this.cookies = cookies;
        this.byteData = byteData;
        this.compositeByteData = compositeByteData;
        this.stringData = stringData;
        this.byteBufferData = byteBufferData;
        this.byteBufData = byteBufData;
        this.streamData = streamData;
        this.bodyGenerator = bodyGenerator;
        this.formParams = formParams;
        this.bodyParts = bodyParts;
        this.virtualHost = virtualHost;
        this.proxyServer = proxyServer;
        this.realm = realm;
        this.file = file;
        this.followRedirect = followRedirect;
        this.requestTimeout = requestTimeout == null ? Duration.ZERO : requestTimeout;
        this.readTimeout = readTimeout == null ? Duration.ZERO : readTimeout;
        this.rangeOffset = rangeOffset;
        this.charset = charset;
        this.channelPoolPartitioning = channelPoolPartitioning;
        this.nameResolver = nameResolver;
    }

    @Override
    public String getUrl() {
        return uri.toUrl();
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public @Nullable InetAddress getAddress() {
        return address;
    }

    @Override
    public @Nullable InetAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public List<Cookie> getCookies() {
        return cookies;
    }

    @Override
    public byte @Nullable [] getByteData() {
        return byteData;
    }

    @Override
    public @Nullable List<byte[]> getCompositeByteData() {
        return compositeByteData;
    }

    @Override
    public @Nullable String getStringData() {
        return stringData;
    }

    @Override
    public @Nullable ByteBuffer getByteBufferData() {
        return byteBufferData;
    }

    @Override
    public @Nullable ByteBuf getByteBufData() {
        return byteBufData;
    }

    @Override
    public @Nullable InputStream getStreamData() {
        return streamData;
    }

    @Override
    public @Nullable BodyGenerator getBodyGenerator() {
        return bodyGenerator;
    }

    @Override
    public List<Param> getFormParams() {
        return formParams;
    }

    @Override
    public List<Part> getBodyParts() {
        return bodyParts;
    }

    @Override
    public @Nullable String getVirtualHost() {
        return virtualHost;
    }

    @Override
    public @Nullable ProxyServer getProxyServer() {
        return proxyServer;
    }

    @Override
    public @Nullable Realm getRealm() {
        return realm;
    }

    @Override
    public @Nullable File getFile() {
        return file;
    }

    @Override
    public @Nullable Boolean getFollowRedirect() {
        return followRedirect;
    }

    @Override
    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    @Override
    public Duration getReadTimeout() {
        return readTimeout;
    }

    @Override
    public long getRangeOffset() {
        return rangeOffset;
    }

    @Override
    public @Nullable Charset getCharset() {
        return charset;
    }

    @Override
    public ChannelPoolPartitioning getChannelPoolPartitioning() {
        return channelPoolPartitioning;
    }

    @Override
    public NameResolver<InetAddress> getNameResolver() {
        return nameResolver;
    }

    @Override
    public List<Param> getQueryParams() {
        // lazy load
        if (queryParams == null) {
            if (isNonEmpty(uri.getQuery())) {
                queryParams = new ArrayList<>(1);
                for (String queryStringParam : uri.getQuery().split("&")) {
                    int pos = queryStringParam.indexOf('=');
                    if (pos <= 0) {
                        queryParams.add(new Param(queryStringParam, null));
                    } else {
                        queryParams.add(new Param(queryStringParam.substring(0, pos), queryStringParam.substring(pos + 1)));
                    }
                }
            } else {
                queryParams = Collections.emptyList();
            }
        }
        return queryParams;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getUrl());
        sb.append('\t');
        sb.append(method);
        sb.append("\theaders:");

        if (!headers.isEmpty()) {
            for (Map.Entry<String, String> header : headers) {
                sb.append('\t');
                sb.append(header.getKey());
                sb.append(':');
                sb.append(header.getValue());
            }
        }

        if (isNonEmpty(formParams)) {
            sb.append("\tformParams:");
            for (Param param : formParams) {
                sb.append('\t');
                sb.append(param.getName());
                sb.append(':');
                sb.append(param.getValue());
            }
        }
        return sb.toString();
    }
}
