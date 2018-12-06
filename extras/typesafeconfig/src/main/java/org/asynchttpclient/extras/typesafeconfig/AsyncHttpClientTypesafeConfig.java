/*
 * Copyright (c) 2018 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.extras.typesafeconfig;

import com.typesafe.config.Config;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.SslEngineFactory;
import org.asynchttpclient.channel.ChannelPool;
import org.asynchttpclient.channel.DefaultKeepAliveStrategy;
import org.asynchttpclient.channel.KeepAliveStrategy;
import org.asynchttpclient.config.AsyncHttpClientConfigDefaults;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.cookie.ThreadSafeCookieStore;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.netty.channel.ConnectionSemaphoreFactory;
import org.asynchttpclient.proxy.ProxyServerSelector;

import java.util.*;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.*;

public class AsyncHttpClientTypesafeConfig implements AsyncHttpClientConfig {

  private final Config config;

  public AsyncHttpClientTypesafeConfig(Config config) {
    this.config = config;
  }

  @Override
  public String getAhcVersion() {
    return AsyncHttpClientConfigDefaults.AHC_VERSION;
  }

  @Override
  public String getThreadPoolName() {
    return getStringOpt(THREAD_POOL_NAME_CONFIG).orElse(defaultThreadPoolName());
  }

  @Override
  public int getMaxConnections() {
    return getIntegerOpt(MAX_CONNECTIONS_CONFIG).orElse(defaultMaxConnections());
  }

  @Override
  public int getMaxConnectionsPerHost() {
    return getIntegerOpt(MAX_CONNECTIONS_PER_HOST_CONFIG).orElse(defaultMaxConnectionsPerHost());
  }

  @Override
  public int getAcquireFreeChannelTimeout() {
    return getIntegerOpt(ACQUIRE_FREE_CHANNEL_TIMEOUT).orElse(defaultAcquireFreeChannelTimeout());
  }

  @Override
  public int getConnectTimeout() {
    return getIntegerOpt(CONNECTION_TIMEOUT_CONFIG).orElse(defaultConnectTimeout());
  }

  @Override
  public int getReadTimeout() {
    return getIntegerOpt(READ_TIMEOUT_CONFIG).orElse(defaultReadTimeout());
  }

  @Override
  public int getPooledConnectionIdleTimeout() {
    return getIntegerOpt(POOLED_CONNECTION_IDLE_TIMEOUT_CONFIG).orElse(defaultPooledConnectionIdleTimeout());
  }

  @Override
  public int getConnectionPoolCleanerPeriod() {
    return getIntegerOpt(CONNECTION_POOL_CLEANER_PERIOD_CONFIG).orElse(defaultConnectionPoolCleanerPeriod());
  }

  @Override
  public int getRequestTimeout() {
    return getIntegerOpt(REQUEST_TIMEOUT_CONFIG).orElse(defaultRequestTimeout());
  }

  @Override
  public boolean isFollowRedirect() {
    return getBooleanOpt(FOLLOW_REDIRECT_CONFIG).orElse(defaultFollowRedirect());
  }

  @Override
  public int getMaxRedirects() {
    return getIntegerOpt(MAX_REDIRECTS_CONFIG).orElse(defaultMaxRedirects());
  }

  @Override
  public boolean isKeepAlive() {
    return getBooleanOpt(KEEP_ALIVE_CONFIG).orElse(defaultKeepAlive());
  }

  @Override
  public String getUserAgent() {
    return getStringOpt(USER_AGENT_CONFIG).orElse(defaultUserAgent());
  }

  @Override
  public boolean isCompressionEnforced() {
    return getBooleanOpt(COMPRESSION_ENFORCED_CONFIG).orElse(defaultCompressionEnforced());
  }

  @Override
  public ThreadFactory getThreadFactory() {
    return null;
  }

  @Override
  public ProxyServerSelector getProxyServerSelector() {
    return ProxyServerSelector.NO_PROXY_SELECTOR;
  }

  @Override
  public SslContext getSslContext() {
    return null;
  }

  @Override
  public Realm getRealm() {
    return null;
  }

  @Override
  public List<RequestFilter> getRequestFilters() {
    return new LinkedList<>();
  }

  @Override
  public List<ResponseFilter> getResponseFilters() {
    return new LinkedList<>();
  }

  @Override
  public List<IOExceptionFilter> getIoExceptionFilters() {
    return new LinkedList<>();
  }

  @Override
  public CookieStore getCookieStore() {
    return new ThreadSafeCookieStore();
  }

  @Override
  public int getMaxRequestRetry() {
    return getIntegerOpt(MAX_REQUEST_RETRY_CONFIG).orElse(defaultMaxRequestRetry());
  }

  @Override
  public boolean isDisableUrlEncodingForBoundRequests() {
    return getBooleanOpt(DISABLE_URL_ENCODING_FOR_BOUND_REQUESTS_CONFIG).orElse(defaultDisableUrlEncodingForBoundRequests());
  }

  @Override
  public boolean isUseLaxCookieEncoder() {
    return getBooleanOpt(USE_LAX_COOKIE_ENCODER_CONFIG).orElse(defaultUseLaxCookieEncoder());
  }

  @Override
  public boolean isStrict302Handling() {
    return getBooleanOpt(STRICT_302_HANDLING_CONFIG).orElse(defaultStrict302Handling());
  }

  @Override
  public int getConnectionTtl() {
    return getIntegerOpt(CONNECTION_TTL_CONFIG).orElse(defaultConnectionTtl());
  }

  @Override
  public boolean isUseOpenSsl() {
    return getBooleanOpt(USE_OPEN_SSL_CONFIG).orElse(defaultUseOpenSsl());
  }

  @Override
  public boolean isUseInsecureTrustManager() {
    return getBooleanOpt(USE_INSECURE_TRUST_MANAGER_CONFIG).orElse(defaultUseInsecureTrustManager());
  }

  @Override
  public boolean isDisableHttpsEndpointIdentificationAlgorithm() {
    return getBooleanOpt(DISABLE_HTTPS_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG).orElse(defaultDisableHttpsEndpointIdentificationAlgorithm());
  }

  @Override
  public String[] getEnabledProtocols() {
    return getListOpt(ENABLED_PROTOCOLS_CONFIG).map(list -> list.toArray(new String[0])).orElse(defaultEnabledProtocols());
  }

  @Override
  public String[] getEnabledCipherSuites() {
    return getListOpt(ENABLED_CIPHER_SUITES_CONFIG).map(list -> list.toArray(new String[0])).orElse(defaultEnabledCipherSuites());
  }

  @Override
  public boolean isFilterInsecureCipherSuites() {
    return getBooleanOpt(FILTER_INSECURE_CIPHER_SUITES_CONFIG).orElse(defaultFilterInsecureCipherSuites());
  }

  @Override
  public int getSslSessionCacheSize() {
    return getIntegerOpt(SSL_SESSION_CACHE_SIZE_CONFIG).orElse(defaultSslSessionCacheSize());
  }

  @Override
  public int getSslSessionTimeout() {
    return getIntegerOpt(SSL_SESSION_TIMEOUT_CONFIG).orElse(defaultSslSessionTimeout());
  }

  @Override
  public int getHttpClientCodecMaxInitialLineLength() {
    return getIntegerOpt(HTTP_CLIENT_CODEC_MAX_INITIAL_LINE_LENGTH_CONFIG).orElse(defaultHttpClientCodecMaxInitialLineLength());
  }

  @Override
  public int getHttpClientCodecMaxHeaderSize() {
    return getIntegerOpt(HTTP_CLIENT_CODEC_MAX_HEADER_SIZE_CONFIG).orElse(defaultHttpClientCodecMaxHeaderSize());
  }

  @Override
  public int getHttpClientCodecMaxChunkSize() {
    return getIntegerOpt(HTTP_CLIENT_CODEC_MAX_CHUNK_SIZE_CONFIG).orElse(defaultHttpClientCodecMaxChunkSize());
  }

  @Override
  public int getHttpClientCodecInitialBufferSize() {
    return getIntegerOpt(HTTP_CLIENT_CODEC_INITIAL_BUFFER_SIZE_CONFIG).orElse(defaultHttpClientCodecInitialBufferSize());
  }

  @Override
  public boolean isDisableZeroCopy() {
    return getBooleanOpt(DISABLE_ZERO_COPY_CONFIG).orElse(defaultDisableZeroCopy());
  }

  @Override
  public int getHandshakeTimeout() {
    return getIntegerOpt(HANDSHAKE_TIMEOUT_CONFIG).orElse(defaultHandshakeTimeout());
  }

  @Override
  public SslEngineFactory getSslEngineFactory() {
    return null;
  }

  @Override
  public int getChunkedFileChunkSize() {
    return getIntegerOpt(CHUNKED_FILE_CHUNK_SIZE_CONFIG).orElse(defaultChunkedFileChunkSize());
  }

  @Override
  public int getWebSocketMaxBufferSize() {
    return getIntegerOpt(WEBSOCKET_MAX_BUFFER_SIZE_CONFIG).orElse(defaultWebSocketMaxBufferSize());
  }

  @Override
  public int getWebSocketMaxFrameSize() {
    return getIntegerOpt(WEBSOCKET_MAX_FRAME_SIZE_CONFIG).orElse(defaultWebSocketMaxFrameSize());
  }

  @Override
  public boolean isKeepEncodingHeader() {
    return getBooleanOpt(KEEP_ENCODING_HEADER_CONFIG).orElse(defaultKeepEncodingHeader());
  }

  @Override
  public int getShutdownQuietPeriod() {
    return getIntegerOpt(SHUTDOWN_QUIET_PERIOD_CONFIG).orElse(defaultShutdownQuietPeriod());
  }

  @Override
  public int getShutdownTimeout() {
    return getIntegerOpt(SHUTDOWN_TIMEOUT_CONFIG).orElse(defaultShutdownTimeout());
  }

  @Override
  public Map<ChannelOption<Object>, Object> getChannelOptions() {
    return Collections.emptyMap();
  }

  @Override
  public EventLoopGroup getEventLoopGroup() {
    return null;
  }

  @Override
  public boolean isUseNativeTransport() {
    return getBooleanOpt(USE_NATIVE_TRANSPORT_CONFIG).orElse(defaultUseNativeTransport());
  }

  @Override
  public Consumer<Channel> getHttpAdditionalChannelInitializer() {
    return null;
  }

  @Override
  public Consumer<Channel> getWsAdditionalChannelInitializer() {
    return null;
  }

  @Override
  public ResponseBodyPartFactory getResponseBodyPartFactory() {
    return ResponseBodyPartFactory.EAGER;
  }

  @Override
  public ChannelPool getChannelPool() {
    return null;
  }

  @Override
  public ConnectionSemaphoreFactory getConnectionSemaphoreFactory() {
    return null;
  }

  @Override
  public Timer getNettyTimer() {
    return null;
  }

  @Override
  public KeepAliveStrategy getKeepAliveStrategy() {
    return new DefaultKeepAliveStrategy();
  }

  @Override
  public boolean isValidateResponseHeaders() {
    return getBooleanOpt(VALIDATE_RESPONSE_HEADERS_CONFIG).orElse(defaultValidateResponseHeaders());
  }

  @Override
  public boolean isAggregateWebSocketFrameFragments() {
    return getBooleanOpt(AGGREGATE_WEBSOCKET_FRAME_FRAGMENTS_CONFIG).orElse(defaultAggregateWebSocketFrameFragments());
  }

  @Override
  public boolean isEnableWebSocketCompression() {
    return getBooleanOpt(ENABLE_WEBSOCKET_COMPRESSION_CONFIG).orElse(defaultEnableWebSocketCompression());
  }

  @Override
  public boolean isTcpNoDelay() {
    return getBooleanOpt(TCP_NO_DELAY_CONFIG).orElse(defaultTcpNoDelay());
  }

  @Override
  public boolean isSoReuseAddress() {
    return getBooleanOpt(SO_REUSE_ADDRESS_CONFIG).orElse(defaultSoReuseAddress());
  }

  @Override
  public int getSoLinger() {
    return getIntegerOpt(SO_LINGER_CONFIG).orElse(defaultSoLinger());
  }

  @Override
  public int getSoSndBuf() {
    return getIntegerOpt(SO_SND_BUF_CONFIG).orElse(defaultSoSndBuf());
  }

  @Override
  public int getSoRcvBuf() {
    return getIntegerOpt(SO_RCV_BUF_CONFIG).orElse(defaultSoRcvBuf());
  }

  @Override
  public ByteBufAllocator getAllocator() {
    return null;
  }

  @Override
  public int getIoThreadsCount() {
    return getIntegerOpt(IO_THREADS_COUNT_CONFIG).orElse(defaultIoThreadsCount());
  }

  private Optional<String> getStringOpt(String key) {
    return getOpt(config::getString, key);
  }

  private Optional<Boolean> getBooleanOpt(String key) {
    return getOpt(config::getBoolean, key);
  }

  private Optional<Integer> getIntegerOpt(String key) {
    return getOpt(config::getInt, key);
  }

  private Optional<List<String>> getListOpt(String key) {
    return getOpt(config::getStringList, key);
  }

  private <T> Optional<T> getOpt(Function<String, T> func, String key) {
    return config.hasPath(key)
            ? Optional.ofNullable(func.apply(key))
            : Optional.empty();
  }
}
