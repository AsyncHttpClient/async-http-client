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
   * Return the delay in milliseconds to evict expired cookies from {@linkplain CookieStore}
   *
   * @return the delay in milliseconds to evict expired cookies from {@linkplain CookieStore}
   */
  int expiredCookieEvictionDelay();

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

  /**
   * Returns whether OpenSSL should be used instead of the JDK SSL implementation.
   * OpenSSL can provide better performance in some scenarios.
   *
   * @return {@code true} if OpenSSL should be used, {@code false} to use JDK SSL
   */
  boolean isUseOpenSsl();

  /**
   * Returns whether to use an insecure trust manager that accepts all certificates.
   * This should only be used for testing purposes and never in production.
   *
   * @return {@code true} if insecure trust manager is enabled, {@code false} otherwise
   */
  boolean isUseInsecureTrustManager();

  /**
   * Returns whether to disable HTTPS endpoint identification algorithm.
   * When enabled, this disables hostname verification and SNI.
   * This should only be used for testing and never in production.
   *
   * @return {@code true} to disable all HTTPS endpoint identification, {@code false} otherwise
   */
  boolean isDisableHttpsEndpointIdentificationAlgorithm();

  /**
   * Returns the array of enabled SSL/TLS protocols.
   *
   * @return the array of enabled protocols, or {@code null} to use defaults
   */
  String[] getEnabledProtocols();

  /**
   * Returns the array of enabled cipher suites for SSL/TLS connections.
   *
   * @return the array of enabled cipher suites, or {@code null} to use defaults
   */
  String[] getEnabledCipherSuites();

  /**
   * Returns whether insecure cipher suites should be filtered out.
   * This is only used when enabled cipher suites are not explicitly set.
   *
   * @return {@code true} if insecure cipher suites must be filtered out, {@code false} otherwise
   */
  boolean isFilterInsecureCipherSuites();

  /**
   * Returns the size of the SSL session cache.
   *
   * @return the size of the SSL session cache, or 0 to use the default value
   */
  int getSslSessionCacheSize();

  /**
   * Returns the SSL session timeout in seconds.
   *
   * @return the SSL session timeout in seconds, or 0 to use the default value
   */
  int getSslSessionTimeout();

  /**
   * Returns the maximum length of the initial line in the HTTP codec.
   *
   * @return the maximum initial line length in bytes
   */
  int getHttpClientCodecMaxInitialLineLength();

  /**
   * Returns the maximum size of HTTP headers in the HTTP codec.
   *
   * @return the maximum header size in bytes
   */
  int getHttpClientCodecMaxHeaderSize();

  /**
   * Returns the maximum size of HTTP chunks in the HTTP codec.
   *
   * @return the maximum chunk size in bytes
   */
  int getHttpClientCodecMaxChunkSize();

  /**
   * Returns the initial buffer size for the HTTP codec.
   *
   * @return the initial buffer size in bytes
   */
  int getHttpClientCodecInitialBufferSize();

  /**
   * Returns whether zero-copy file transfer is disabled.
   * Zero-copy can improve performance but may not work in all scenarios.
   *
   * @return {@code true} if zero-copy is disabled, {@code false} if enabled
   */
  boolean isDisableZeroCopy();

  /**
   * Returns the SSL/TLS handshake timeout in milliseconds.
   *
   * @return the handshake timeout in milliseconds
   */
  int getHandshakeTimeout();

  /**
   * Returns the factory for creating SSL engines.
   *
   * @return the {@link SslEngineFactory}, or {@code null} if not configured
   */
  SslEngineFactory getSslEngineFactory();

  /**
   * Returns the chunk size for chunked file uploads.
   *
   * @return the chunk size in bytes
   */
  int getChunkedFileChunkSize();

  /**
   * Returns the maximum buffer size for WebSocket connections.
   *
   * @return the maximum WebSocket buffer size in bytes
   */
  int getWebSocketMaxBufferSize();

  /**
   * Returns the maximum frame size for WebSocket connections.
   *
   * @return the maximum WebSocket frame size in bytes
   */
  int getWebSocketMaxFrameSize();

  /**
   * Returns whether the encoding header should be kept when compression is enabled.
   *
   * @return {@code true} if encoding header should be kept, {@code false} otherwise
   */
  boolean isKeepEncodingHeader();

  /**
   * Returns the quiet period for graceful shutdown in milliseconds.
   * During the quiet period, no new tasks will be accepted.
   *
   * @return the shutdown quiet period in milliseconds
   */
  int getShutdownQuietPeriod();

  /**
   * Returns the maximum time to wait for shutdown to complete in milliseconds.
   *
   * @return the shutdown timeout in milliseconds
   */
  int getShutdownTimeout();

  /**
   * Returns the Netty channel options to be applied to all channels.
   *
   * @return a map of channel options and their values
   */
  Map<ChannelOption<Object>, Object> getChannelOptions();

  /**
   * Returns the Netty event loop group to be used.
   * If {@code null}, a default event loop group will be created.
   *
   * @return the {@link EventLoopGroup}, or {@code null} to use default
   */
  EventLoopGroup getEventLoopGroup();

  /**
   * Returns whether native transport should be used when available.
   * Native transports can provide better performance on supported platforms.
   *
   * @return {@code true} to use native transport, {@code false} to use NIO
   */
  boolean isUseNativeTransport();

  /**
   * Returns the additional channel initializer for HTTP channels.
   *
   * @return the channel initializer consumer, or {@code null} if not configured
   */
  Consumer<Channel> getHttpAdditionalChannelInitializer();

  /**
   * Returns the additional channel initializer for WebSocket channels.
   *
   * @return the channel initializer consumer, or {@code null} if not configured
   */
  Consumer<Channel> getWsAdditionalChannelInitializer();

  /**
   * Returns the factory for creating response body part objects.
   *
   * @return the {@link ResponseBodyPartFactory}
   */
  ResponseBodyPartFactory getResponseBodyPartFactory();

  /**
   * Returns the custom channel pool implementation.
   *
   * @return the {@link ChannelPool}, or {@code null} to use default
   */
  ChannelPool getChannelPool();

  /**
   * Returns the factory for creating connection semaphores.
   *
   * @return the {@link ConnectionSemaphoreFactory}, or {@code null} if not configured
   */
  ConnectionSemaphoreFactory getConnectionSemaphoreFactory();

  /**
   * Returns the Netty timer to be used for timeouts.
   *
   * @return the {@link Timer}, or {@code null} to create a default timer
   */
  Timer getNettyTimer();

  /**
   * Returns the duration between ticks of the hashed wheel timer in milliseconds.
   * The hashed wheel timer is used for managing timeouts efficiently.
   *
   * @return the duration between ticks of {@link io.netty.util.HashedWheelTimer} in milliseconds
   */
  long getHashedWheelTimerTickDuration();

  /**
   * Returns the size of the hashed wheel timer.
   * A larger size can handle more concurrent timeouts but uses more memory.
   *
   * @return the size of the hashed wheel {@link io.netty.util.HashedWheelTimer}
   */
  int getHashedWheelTimerSize();

  /**
   * Returns the strategy for determining whether connections should be kept alive.
   *
   * @return the {@link KeepAliveStrategy}
   */
  KeepAliveStrategy getKeepAliveStrategy();

  /**
   * Returns whether response headers should be validated for correctness.
   *
   * @return {@code true} if response headers should be validated, {@code false} otherwise
   */
  boolean isValidateResponseHeaders();

  /**
   * Returns whether WebSocket frame fragments should be aggregated.
   *
   * @return {@code true} if fragments should be aggregated, {@code false} otherwise
   */
  boolean isAggregateWebSocketFrameFragments();

  /**
   * Returns whether WebSocket compression extension is enabled.
   *
   * @return {@code true} if WebSocket compression is enabled, {@code false} otherwise
   */
  boolean isEnableWebSocketCompression();

  /**
   * Returns whether TCP_NODELAY socket option is enabled.
   * When enabled, small packets are sent immediately without buffering (Nagle's algorithm disabled).
   *
   * @return {@code true} if TCP_NODELAY is enabled, {@code false} otherwise
   */
  boolean isTcpNoDelay();

  /**
   * Returns whether SO_REUSEADDR socket option is enabled.
   * When enabled, allows reusing local addresses and ports.
   *
   * @return {@code true} if SO_REUSEADDR is enabled, {@code false} otherwise
   */
  boolean isSoReuseAddress();

  /**
   * Returns whether SO_KEEPALIVE socket option is enabled.
   * When enabled, TCP keepalive probes are sent to detect dead connections.
   *
   * @return {@code true} if SO_KEEPALIVE is enabled, {@code false} otherwise
   */
  boolean isSoKeepAlive();

  /**
   * Returns the SO_LINGER socket option value in seconds.
   * Controls the behavior when closing a socket with unsent data.
   *
   * @return the SO_LINGER value in seconds, or -1 if disabled
   */
  int getSoLinger();

  /**
   * Returns the SO_SNDBUF socket option value.
   * Specifies the size of the TCP send buffer.
   *
   * @return the send buffer size in bytes, or -1 to use system default
   */
  int getSoSndBuf();

  /**
   * Returns the SO_RCVBUF socket option value.
   * Specifies the size of the TCP receive buffer.
   *
   * @return the receive buffer size in bytes, or -1 to use system default
   */
  int getSoRcvBuf();

  /**
   * Returns the Netty ByteBuf allocator to use for buffer allocation.
   *
   * @return the {@link ByteBufAllocator}, or {@code null} to use default
   */
  ByteBufAllocator getAllocator();

  /**
   * Returns the number of I/O threads to use in the event loop group.
   *
   * @return the number of I/O threads
   */
  int getIoThreadsCount();

  /**
   * Factory for creating {@link HttpResponseBodyPart} instances.
   * Determines how response body data is buffered and processed.
   */
  enum ResponseBodyPartFactory {

    /**
     * Creates eager response body parts that immediately copy the data from the Netty buffer.
     * This is safer but uses more memory as data is copied immediately.
     */
    EAGER {
      @Override
      public HttpResponseBodyPart newResponseBodyPart(ByteBuf buf, boolean last) {
        return new EagerResponseBodyPart(buf, last);
      }
    },

    /**
     * Creates lazy response body parts that hold a reference to the Netty buffer.
     * This is more memory efficient but requires careful buffer lifecycle management.
     */
    LAZY {
      @Override
      public HttpResponseBodyPart newResponseBodyPart(ByteBuf buf, boolean last) {
        return new LazyResponseBodyPart(buf, last);
      }
    };

    /**
     * Creates a new response body part from the given buffer.
     *
     * @param buf the buffer containing response body data
     * @param last {@code true} if this is the last body part, {@code false} otherwise
     * @return a new {@link HttpResponseBodyPart}
     */
    public abstract HttpResponseBodyPart newResponseBodyPart(ByteBuf buf, boolean last);
  }
}
