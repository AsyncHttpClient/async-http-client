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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.Timer;
import org.asynchttpclient.channel.ChannelPool;
import org.asynchttpclient.channel.KeepAliveStrategy;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.netty.EagerResponseBodyPart;
import org.asynchttpclient.netty.LazyResponseBodyPart;
import org.asynchttpclient.netty.channel.ConnectionSemaphoreFactory;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyServerSelector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

public interface AsyncHttpClientConfig {

  /**
   * @return the version of AHC
   */
  String getAhcVersion();

  /**
   * Return the name of {@link AsyncHttpClient}, which is used for thread naming and debugging.
   *
   * @return the name.
   */
  String getThreadPoolName();

  /**
   * Return the maximum number of connections an {@link AsyncHttpClient} can handle.
   *
   * @return the maximum number of connections an {@link AsyncHttpClient} can handle.
   */
  int getMaxConnections();

  /**
   * Return the maximum number of connections per hosts an {@link AsyncHttpClient} can handle.
   *
   * @return the maximum number of connections per host an {@link AsyncHttpClient} can handle.
   */
  int getMaxConnectionsPerHost();

  /**
   * Return the maximum duration in milliseconds an {@link AsyncHttpClient} can wait to acquire a free channel
   *
   * @return Return the maximum duration in milliseconds an {@link AsyncHttpClient} can wait to acquire a free channel
   */
  int getAcquireFreeChannelTimeout();


  /**
   * Return the maximum time in millisecond an {@link AsyncHttpClient} can wait when connecting to a remote host
   *
   * @return the maximum time in millisecond an {@link AsyncHttpClient} can wait when connecting to a remote host
   */
  int getConnectTimeout();

  /**
   * Return the maximum time in millisecond an {@link AsyncHttpClient} can stay idle.
   *
   * @return the maximum time in millisecond an {@link AsyncHttpClient} can stay idle.
   */
  int getReadTimeout();

  /**
   * Return the maximum time in millisecond an {@link AsyncHttpClient} will keep connection in pool.
   *
   * @return the maximum time in millisecond an {@link AsyncHttpClient} will keep connection in pool.
   */
  int getPooledConnectionIdleTimeout();

  /**
   * @return the period in millis to clean the pool of dead and idle connections.
   */
  int getConnectionPoolCleanerPeriod();

  /**
   * Return the maximum time in millisecond an {@link AsyncHttpClient} waits until the response is completed.
   *
   * @return the maximum time in millisecond an {@link AsyncHttpClient} waits until the response is completed.
   */
  int getRequestTimeout();

  /**
   * Is HTTP redirect enabled
   *
   * @return true if enabled.
   */
  boolean isFollowRedirect();

  /**
   * Get the maximum number of HTTP redirect
   *
   * @return the maximum number of HTTP redirect
   */
  int getMaxRedirects();

  /**
   * Is the {@link ChannelPool} support enabled.
   *
   * @return true if keep-alive is enabled
   */
  boolean isKeepAlive();

  /**
   * Return the USER_AGENT header value
   *
   * @return the USER_AGENT header value
   */
  String getUserAgent();

  /**
   * Is HTTP compression enforced.
   *
   * @return true if compression is enforced
   */
  boolean isCompressionEnforced();

  /**
   * Return the {@link java.util.concurrent.ThreadFactory} an {@link AsyncHttpClient} use for handling asynchronous response.
   *
   * @return the {@link java.util.concurrent.ThreadFactory} an {@link AsyncHttpClient} use for handling asynchronous response. If no {@link ThreadFactory} has been explicitly
   * provided, this method will return <code>null</code>
   */
  ThreadFactory getThreadFactory();

  /**
   * An instance of {@link ProxyServer} used by an {@link AsyncHttpClient}
   *
   * @return instance of {@link ProxyServer}
   */
  ProxyServerSelector getProxyServerSelector();

  /**
   * Return an instance of {@link SslContext} used for SSL connection.
   *
   * @return an instance of {@link SslContext} used for SSL connection.
   */
  SslContext getSslContext();

  /**
   * Return the current {@link Realm}
   *
   * @return the current {@link Realm}
   */
  Realm getRealm();

  /**
   * Return the list of {@link RequestFilter}
   *
   * @return Unmodifiable list of {@link RequestFilter}
   */
  List<RequestFilter> getRequestFilters();

  /**
   * Return the list of {@link ResponseFilter}
   *
   * @return Unmodifiable list of {@link ResponseFilter}
   */
  List<ResponseFilter> getResponseFilters();

  /**
   * Return the list of {@link java.io.IOException}
   *
   * @return Unmodifiable list of {@link java.io.IOException}
   */
  List<IOExceptionFilter> getIoExceptionFilters();

  /**
   * Return cookie store that is used to store and retrieve cookies
   *
   * @return {@link CookieStore} object
   */
  CookieStore getCookieStore();

  /**
   * Return the number of time the library will retry when an {@link java.io.IOException} is throw by the remote server
   *
   * @return the number of time the library will retry when an {@link java.io.IOException} is throw by the remote server
   */
  int getMaxRequestRetry();

  /**
   * @return the disableUrlEncodingForBoundRequests
   */
  boolean isDisableUrlEncodingForBoundRequests();

  /**
   * @return true if AHC is to use a LAX cookie encoder, eg accept illegal chars in cookie value
   */
  boolean isUseLaxCookieEncoder();

  /**
   * In the case of a POST/Redirect/Get scenario where the server uses a 302 for the redirect, should AHC respond to the redirect with a GET or whatever the original method was.
   * Unless configured otherwise, for a 302, AHC, will use a GET for this case.
   *
   * @return <code>true</code> if strict 302 handling is to be used, otherwise <code>false</code>.
   */
  boolean isStrict302Handling();

  /**
   * @return the maximum time in millisecond an {@link AsyncHttpClient} will keep connection in the pool, or -1 to keep connection while possible.
   */
  int getConnectionTtl();

  boolean isUseOpenSsl();

  boolean isUseInsecureTrustManager();

  /**
   * @return true to disable all HTTPS behaviors AT ONCE, such as hostname verification and SNI
   */
  boolean isDisableHttpsEndpointIdentificationAlgorithm();

  /**
   * @return the array of enabled protocols
   */
  String[] getEnabledProtocols();

  /**
   * @return the array of enabled cipher suites
   */
  String[] getEnabledCipherSuites();

  /**
   * @return if insecured cipher suites must be filtered out (only used when not explicitly passing enabled cipher suites)
   */
  boolean isFilterInsecureCipherSuites();

  /**
   * @return the size of the SSL session cache, 0 means using the default value
   */
  int getSslSessionCacheSize();

  /**
   * @return the SSL session timeout in seconds, 0 means using the default value
   */
  int getSslSessionTimeout();

  int getHttpClientCodecMaxInitialLineLength();

  int getHttpClientCodecMaxHeaderSize();

  int getHttpClientCodecMaxChunkSize();

  int getHttpClientCodecInitialBufferSize();

  boolean isDisableZeroCopy();

  int getHandshakeTimeout();

  SslEngineFactory getSslEngineFactory();

  int getChunkedFileChunkSize();

  int getWebSocketMaxBufferSize();

  int getWebSocketMaxFrameSize();

  boolean isKeepEncodingHeader();

  int getShutdownQuietPeriod();

  int getShutdownTimeout();

  Map<ChannelOption<Object>, Object> getChannelOptions();

  EventLoopGroup getEventLoopGroup();

  boolean isUseNativeTransport();

  Consumer<Channel> getHttpAdditionalChannelInitializer();

  Consumer<Channel> getWsAdditionalChannelInitializer();

  ResponseBodyPartFactory getResponseBodyPartFactory();

  ChannelPool getChannelPool();

  ConnectionSemaphoreFactory getConnectionSemaphoreFactory();

  Timer getNettyTimer();

  KeepAliveStrategy getKeepAliveStrategy();

  boolean isValidateResponseHeaders();

  boolean isAggregateWebSocketFrameFragments();

  boolean isEnableWebSocketCompression();

  boolean isTcpNoDelay();

  boolean isSoReuseAddress();

  int getSoLinger();

  int getSoSndBuf();

  int getSoRcvBuf();

  ByteBufAllocator getAllocator();

  int getIoThreadsCount();

  enum ResponseBodyPartFactory {

    EAGER {
      @Override
      public HttpResponseBodyPart newResponseBodyPart(ByteBuf buf, boolean last) {
        return new EagerResponseBodyPart(buf, last);
      }
    },

    LAZY {
      @Override
      public HttpResponseBodyPart newResponseBodyPart(ByteBuf buf, boolean last) {
        return new LazyResponseBodyPart(buf, last);
      }
    };

    public abstract HttpResponseBodyPart newResponseBodyPart(ByteBuf buf, boolean last);
  }
}
