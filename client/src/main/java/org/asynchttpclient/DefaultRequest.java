/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.resolver.NameResolver;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.request.body.generator.BodyGenerator;
import org.asynchttpclient.request.body.multipart.Part;
import org.asynchttpclient.uri.Uri;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

public class DefaultRequest implements Request {

  public final ProxyServer proxyServer;
  private final String method;
  private final Uri uri;
  private final InetAddress address;
  private final InetAddress localAddress;
  private final HttpHeaders headers;
  private final List<Cookie> cookies;
  private final byte[] byteData;
  private final List<byte[]> compositeByteData;
  private final String stringData;
  private final ByteBuffer byteBufferData;
  private final InputStream streamData;
  private final BodyGenerator bodyGenerator;
  private final List<Param> formParams;
  private final List<Part> bodyParts;
  private final String virtualHost;
  private final Realm realm;
  private final File file;
  private final Boolean followRedirect;
  private final int requestTimeout;
  private final int readTimeout;
  private final long rangeOffset;
  private final Charset charset;
  private final ChannelPoolPartitioning channelPoolPartitioning;
  private final NameResolver<InetAddress> nameResolver;
  // lazily loaded
  private List<Param> queryParams;

  public DefaultRequest(String method,
                        Uri uri,
                        InetAddress address,
                        InetAddress localAddress,
                        HttpHeaders headers,
                        List<Cookie> cookies,
                        byte[] byteData,
                        List<byte[]> compositeByteData,
                        String stringData,
                        ByteBuffer byteBufferData,
                        InputStream streamData,
                        BodyGenerator bodyGenerator,
                        List<Param> formParams,
                        List<Part> bodyParts,
                        String virtualHost,
                        ProxyServer proxyServer,
                        Realm realm,
                        File file,
                        Boolean followRedirect,
                        int requestTimeout,
                        int readTimeout,
                        long rangeOffset,
                        Charset charset,
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
    this.streamData = streamData;
    this.bodyGenerator = bodyGenerator;
    this.formParams = formParams;
    this.bodyParts = bodyParts;
    this.virtualHost = virtualHost;
    this.proxyServer = proxyServer;
    this.realm = realm;
    this.file = file;
    this.followRedirect = followRedirect;
    this.requestTimeout = requestTimeout;
    this.readTimeout = readTimeout;
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
  public InetAddress getAddress() {
    return address;
  }

  @Override
  public InetAddress getLocalAddress() {
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
  public byte[] getByteData() {
    return byteData;
  }

  @Override
  public List<byte[]> getCompositeByteData() {
    return compositeByteData;
  }

  @Override
  public String getStringData() {
    return stringData;
  }

  @Override
  public ByteBuffer getByteBufferData() {
    return byteBufferData;
  }

  @Override
  public InputStream getStreamData() {
    return streamData;
  }

  @Override
  public BodyGenerator getBodyGenerator() {
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
  public String getVirtualHost() {
    return virtualHost;
  }

  @Override
  public ProxyServer getProxyServer() {
    return proxyServer;
  }

  @Override
  public Realm getRealm() {
    return realm;
  }

  @Override
  public File getFile() {
    return file;
  }

  @Override
  public Boolean getFollowRedirect() {
    return followRedirect;
  }

  @Override
  public int getRequestTimeout() {
    return requestTimeout;
  }

  @Override
  public int getReadTimeout() {
    return readTimeout;
  }

  @Override
  public long getRangeOffset() {
    return rangeOffset;
  }

  @Override
  public Charset getCharset() {
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
    if (queryParams == null)
      // lazy load
      if (isNonEmpty(uri.getQuery())) {
        queryParams = new ArrayList<>(1);
        for (String queryStringParam : uri.getQuery().split("&")) {
          int pos = queryStringParam.indexOf('=');
          if (pos <= 0)
            queryParams.add(new Param(queryStringParam, null));
          else
            queryParams.add(new Param(queryStringParam.substring(0, pos), queryStringParam.substring(pos + 1)));
        }
      } else
        queryParams = Collections.emptyList();
    return queryParams;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder(getUrl());

    sb.append("\t");
    sb.append(method);
    sb.append("\theaders:");
    if (!headers.isEmpty()) {
      for (Map.Entry<String, String> header : headers) {
        sb.append("\t");
        sb.append(header.getKey());
        sb.append(":");
        sb.append(header.getValue());
      }
    }
    if (isNonEmpty(formParams)) {
      sb.append("\tformParams:");
      for (Param param : formParams) {
        sb.append("\t");
        sb.append(param.getName());
        sb.append(":");
        sb.append(param.getValue());
      }
    }

    return sb.toString();
  }
}
