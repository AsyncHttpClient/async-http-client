/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
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
package org.asynchttpclient;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.Timer;
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
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyServerSelector;
import org.asynchttpclient.util.ProxyUtils;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultAcquireFreeChannelTimeout;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultAggregateWebSocketFrameFragments;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultChunkedFileChunkSize;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultCompressionEnforced;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultConnectTimeout;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultConnectionPoolCleanerPeriod;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultConnectionTtl;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultDisableHttpsEndpointIdentificationAlgorithm;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultDisableUrlEncodingForBoundRequests;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultDisableZeroCopy;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultEnableAutomaticDecompression;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultEnableWebSocketCompression;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultEnabledCipherSuites;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultEnabledProtocols;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultExpiredCookieEvictionDelay;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultFilterInsecureCipherSuites;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultFollowRedirect;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultHandshakeTimeout;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultHashedWheelTimerSize;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultHashedWheelTimerTickDuration;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultHttpClientCodecInitialBufferSize;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultHttpClientCodecParseHttpAfterConnectRequest;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultHttpClientCodecAllowDuplicateContentLengths;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultHttpClientCodecMaxChunkSize;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultHttpClientCodecMaxHeaderSize;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultHttpClientCodecMaxInitialLineLength;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultIoThreadsCount;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultKeepAlive;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultKeepEncodingHeader;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultMaxConnections;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultMaxConnectionsPerHost;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultMaxRedirects;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultMaxRequestRetry;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultPooledConnectionIdleTimeout;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultReadTimeout;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultRequestTimeout;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultShutdownQuietPeriod;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultShutdownTimeout;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultSoKeepAlive;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultSoLinger;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultSoRcvBuf;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultSoReuseAddress;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultSoSndBuf;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultSslSessionCacheSize;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultSslSessionTimeout;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultStrict302Handling;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultTcpNoDelay;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultThreadPoolName;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultUseInsecureTrustManager;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultUseLaxCookieEncoder;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultUseNativeTransport;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultUseOnlyEpollNativeTransport;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultUseOpenSsl;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultUseProxyProperties;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultUseProxySelector;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultUserAgent;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultValidateResponseHeaders;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultWebSocketMaxBufferSize;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultWebSocketMaxFrameSize;

/**
 * Configuration class to use with a {@link AsyncHttpClient}. System property can be also used to configure this object default behavior by doing: <br>
 * -Dorg.asynchttpclient.nameOfTheProperty
 *
 * @see AsyncHttpClientConfig for documentation
 */
public class DefaultAsyncHttpClientConfig implements AsyncHttpClientConfig {

    // http
    private final boolean followRedirect;
    private final int maxRedirects;
    private final boolean strict302Handling;
    private final boolean compressionEnforced;

    private final boolean enableAutomaticDecompression;
    private final String userAgent;
    private final @Nullable Realm realm;
    private final int maxRequestRetry;
    private final boolean disableUrlEncodingForBoundRequests;
    private final boolean useLaxCookieEncoder;
    private final boolean disableZeroCopy;
    private final boolean keepEncodingHeader;
    private final ProxyServerSelector proxyServerSelector;
    private final boolean validateResponseHeaders;
    private final boolean stripAuthorizationOnRedirect;

    // websockets
    private final boolean aggregateWebSocketFrameFragments;
    private final boolean enablewebSocketCompression;
    private final int webSocketMaxBufferSize;
    private final int webSocketMaxFrameSize;

    // timeouts
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration readTimeout;
    private final Duration shutdownQuietPeriod;
    private final Duration shutdownTimeout;

    // keep-alive
    private final boolean keepAlive;
    private final Duration pooledConnectionIdleTimeout;
    private final Duration connectionPoolCleanerPeriod;
    private final Duration connectionTtl;
    private final int maxConnections;
    private final int maxConnectionsPerHost;
    private final int acquireFreeChannelTimeout;
    private final @Nullable ChannelPool channelPool;
    private final @Nullable ConnectionSemaphoreFactory connectionSemaphoreFactory;
    private final KeepAliveStrategy keepAliveStrategy;

    // ssl
    private final boolean useOpenSsl;
    private final boolean useInsecureTrustManager;
    private final boolean disableHttpsEndpointIdentificationAlgorithm;
    private final int handshakeTimeout;
    private final @Nullable String[] enabledProtocols;
    private final @Nullable String[] enabledCipherSuites;
    private final boolean filterInsecureCipherSuites;
    private final int sslSessionCacheSize;
    private final int sslSessionTimeout;
    private final @Nullable SslContext sslContext;
    private final @Nullable SslEngineFactory sslEngineFactory;

    // filters
    private final List<RequestFilter> requestFilters;
    private final List<ResponseFilter> responseFilters;
    private final List<IOExceptionFilter> ioExceptionFilters;

    // cookie store
    private final CookieStore cookieStore;
    private final int expiredCookieEvictionDelay;

    // internals
    private final String threadPoolName;
    private final int httpClientCodecMaxInitialLineLength;
    private final int httpClientCodecMaxHeaderSize;
    private final int httpClientCodecMaxChunkSize;
    private final int httpClientCodecInitialBufferSize;
    private final boolean httpClientCodecParseHttpAfterConnectRequest;
    private final boolean httpClientCodecAllowDuplicateContentLengths;
    private final int chunkedFileChunkSize;
    private final Map<ChannelOption<Object>, Object> channelOptions;
    private final @Nullable EventLoopGroup eventLoopGroup;
    private final boolean useNativeTransport;
    private final boolean useOnlyEpollNativeTransport;
    private final @Nullable ByteBufAllocator allocator;
    private final boolean tcpNoDelay;
    private final boolean soReuseAddress;
    private final boolean soKeepAlive;
    private final int soLinger;
    private final int soSndBuf;
    private final int soRcvBuf;
    private final @Nullable Timer nettyTimer;
    private final @Nullable ThreadFactory threadFactory;
    private final @Nullable Consumer<Channel> httpAdditionalChannelInitializer;
    private final @Nullable Consumer<Channel> wsAdditionalChannelInitializer;
    private final ResponseBodyPartFactory responseBodyPartFactory;
    private final int ioThreadsCount;
    private final long hashedWheelTimerTickDuration;
    private final int hashedWheelTimerSize;

    private DefaultAsyncHttpClientConfig(// http
                                         boolean followRedirect,
                                         int maxRedirects,
                                         boolean strict302Handling,
                                         boolean compressionEnforced,
                                         boolean enableAutomaticDecompression,
                                         String userAgent,
                                         @Nullable Realm realm,
                                         int maxRequestRetry,
                                         boolean disableUrlEncodingForBoundRequests,
                                         boolean useLaxCookieEncoder,
                                         boolean disableZeroCopy,
                                         boolean keepEncodingHeader,
                                         ProxyServerSelector proxyServerSelector,
                                         boolean validateResponseHeaders,
                                         boolean aggregateWebSocketFrameFragments,
                                         boolean enablewebSocketCompression,
                                         boolean stripAuthorizationOnRedirect,

                                         // timeouts
                                         Duration connectTimeout,
                                         Duration requestTimeout,
                                         Duration readTimeout,
                                         Duration shutdownQuietPeriod,
                                         Duration shutdownTimeout,

                                         // keep-alive
                                         boolean keepAlive,
                                         Duration pooledConnectionIdleTimeout,
                                         Duration connectionPoolCleanerPeriod,
                                         Duration connectionTtl,
                                         int maxConnections,
                                         int maxConnectionsPerHost,
                                         int acquireFreeChannelTimeout,
                                         @Nullable ChannelPool channelPool,
                                         @Nullable ConnectionSemaphoreFactory connectionSemaphoreFactory,
                                         KeepAliveStrategy keepAliveStrategy,

                                         // ssl
                                         boolean useOpenSsl,
                                         boolean useInsecureTrustManager,
                                         boolean disableHttpsEndpointIdentificationAlgorithm,
                                         int handshakeTimeout,
                                         @Nullable String[] enabledProtocols,
                                         @Nullable String[] enabledCipherSuites,
                                         boolean filterInsecureCipherSuites,
                                         int sslSessionCacheSize,
                                         int sslSessionTimeout,
                                         @Nullable SslContext sslContext,
                                         @Nullable SslEngineFactory sslEngineFactory,

                                         // filters
                                         List<RequestFilter> requestFilters,
                                         List<ResponseFilter> responseFilters,
                                         List<IOExceptionFilter> ioExceptionFilters,

                                         // cookie store
                                         CookieStore cookieStore,
                                         int expiredCookieEvictionDelay,

                                         // tuning
                                         boolean tcpNoDelay,
                                         boolean soReuseAddress,
                                         boolean soKeepAlive,
                                         int soLinger,
                                         int soSndBuf,
                                         int soRcvBuf,

                                         // internals
                                         String threadPoolName,
                                         int httpClientCodecMaxInitialLineLength,
                                         int httpClientCodecMaxHeaderSize,
                                         int httpClientCodecMaxChunkSize,
                                         int httpClientCodecInitialBufferSize,
                                         boolean httpClientCodecParseHttpAfterConnectRequest,
                                         boolean httpClientCodecAllowDuplicateContentLengths,
                                         int chunkedFileChunkSize,
                                         int webSocketMaxBufferSize,
                                         int webSocketMaxFrameSize,
                                         Map<ChannelOption<Object>, Object> channelOptions,
                                         @Nullable EventLoopGroup eventLoopGroup,
                                         boolean useNativeTransport,
                                         boolean useOnlyEpollNativeTransport,
                                         @Nullable ByteBufAllocator allocator,
                                         @Nullable Timer nettyTimer,
                                         @Nullable ThreadFactory threadFactory,
                                         @Nullable Consumer<Channel> httpAdditionalChannelInitializer,
                                         @Nullable Consumer<Channel> wsAdditionalChannelInitializer,
                                         ResponseBodyPartFactory responseBodyPartFactory,
                                         int ioThreadsCount,
                                         long hashedWheelTimerTickDuration,
                                         int hashedWheelTimerSize) {

        // http
        this.followRedirect = followRedirect;
        this.maxRedirects = maxRedirects;
        this.strict302Handling = strict302Handling;
        this.compressionEnforced = compressionEnforced;
        this.enableAutomaticDecompression = enableAutomaticDecompression;
        this.userAgent = userAgent;
        this.realm = realm;
        this.maxRequestRetry = maxRequestRetry;
        this.disableUrlEncodingForBoundRequests = disableUrlEncodingForBoundRequests;
        this.useLaxCookieEncoder = useLaxCookieEncoder;
        this.disableZeroCopy = disableZeroCopy;
        this.keepEncodingHeader = keepEncodingHeader;
        this.proxyServerSelector = proxyServerSelector;
        this.validateResponseHeaders = validateResponseHeaders;
        this.stripAuthorizationOnRedirect = stripAuthorizationOnRedirect;

        // websocket
        this.aggregateWebSocketFrameFragments = aggregateWebSocketFrameFragments;
        this.enablewebSocketCompression = enablewebSocketCompression;
        this.webSocketMaxBufferSize = webSocketMaxBufferSize;
        this.webSocketMaxFrameSize = webSocketMaxFrameSize;

        // timeouts
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.readTimeout = readTimeout;
        this.shutdownQuietPeriod = shutdownQuietPeriod;
        this.shutdownTimeout = shutdownTimeout;

        // keep-alive
        this.keepAlive = keepAlive;
        this.pooledConnectionIdleTimeout = pooledConnectionIdleTimeout;
        this.connectionPoolCleanerPeriod = connectionPoolCleanerPeriod;
        this.connectionTtl = connectionTtl;
        this.maxConnections = maxConnections;
        this.maxConnectionsPerHost = maxConnectionsPerHost;
        this.acquireFreeChannelTimeout = acquireFreeChannelTimeout;
        this.channelPool = channelPool;
        this.connectionSemaphoreFactory = connectionSemaphoreFactory;
        this.keepAliveStrategy = keepAliveStrategy;

        // ssl
        this.useOpenSsl = useOpenSsl;
        this.useInsecureTrustManager = useInsecureTrustManager;
        this.disableHttpsEndpointIdentificationAlgorithm = disableHttpsEndpointIdentificationAlgorithm;
        this.handshakeTimeout = handshakeTimeout;
        this.enabledProtocols = enabledProtocols;
        this.enabledCipherSuites = enabledCipherSuites;
        this.filterInsecureCipherSuites = filterInsecureCipherSuites;
        this.sslSessionCacheSize = sslSessionCacheSize;
        this.sslSessionTimeout = sslSessionTimeout;
        this.sslContext = sslContext;
        this.sslEngineFactory = sslEngineFactory;

        // filters
        this.requestFilters = requestFilters;
        this.responseFilters = responseFilters;
        this.ioExceptionFilters = ioExceptionFilters;

        // cookie store
        this.cookieStore = cookieStore;
        this.expiredCookieEvictionDelay = expiredCookieEvictionDelay;

        // tuning
        this.tcpNoDelay = tcpNoDelay;
        this.soReuseAddress = soReuseAddress;
        this.soKeepAlive = soKeepAlive;
        this.soLinger = soLinger;
        this.soSndBuf = soSndBuf;
        this.soRcvBuf = soRcvBuf;

        // internals
        this.threadPoolName = threadPoolName;
        this.httpClientCodecMaxInitialLineLength = httpClientCodecMaxInitialLineLength;
        this.httpClientCodecMaxHeaderSize = httpClientCodecMaxHeaderSize;
        this.httpClientCodecMaxChunkSize = httpClientCodecMaxChunkSize;
        this.httpClientCodecInitialBufferSize = httpClientCodecInitialBufferSize;
        this.httpClientCodecParseHttpAfterConnectRequest = httpClientCodecParseHttpAfterConnectRequest;
        this.httpClientCodecAllowDuplicateContentLengths = httpClientCodecAllowDuplicateContentLengths;
        this.chunkedFileChunkSize = chunkedFileChunkSize;
        this.channelOptions = channelOptions;
        this.eventLoopGroup = eventLoopGroup;
        this.useNativeTransport = useNativeTransport;
        this.useOnlyEpollNativeTransport = useOnlyEpollNativeTransport;

        if (useOnlyEpollNativeTransport && !useNativeTransport) {
            throw new IllegalArgumentException("Native Transport must be enabled to use Epoll Native Transport only");
        }

        this.allocator = allocator;
        this.nettyTimer = nettyTimer;
        this.threadFactory = threadFactory;
        this.httpAdditionalChannelInitializer = httpAdditionalChannelInitializer;
        this.wsAdditionalChannelInitializer = wsAdditionalChannelInitializer;
        this.responseBodyPartFactory = responseBodyPartFactory;
        this.ioThreadsCount = ioThreadsCount;
        this.hashedWheelTimerTickDuration = hashedWheelTimerTickDuration;
        this.hashedWheelTimerSize = hashedWheelTimerSize;
    }

    @Override
    public String getAhcVersion() {
        return AsyncHttpClientConfigDefaults.AHC_VERSION;
    }

    // http
    @Override
    public boolean isFollowRedirect() {
        return followRedirect;
    }

    @Override
    public int getMaxRedirects() {
        return maxRedirects;
    }

    @Override
    public boolean isStrict302Handling() {
        return strict302Handling;
    }

    @Override
    public boolean isCompressionEnforced() {
        return compressionEnforced;
    }

    @Override
    public boolean isEnableAutomaticDecompression() {
        return enableAutomaticDecompression;
    }

    @Override
    public String getUserAgent() {
        return userAgent;
    }

    @Override
    public @Nullable Realm getRealm() {
        return realm;
    }

    @Override
    public int getMaxRequestRetry() {
        return maxRequestRetry;
    }

    @Override
    public boolean isDisableUrlEncodingForBoundRequests() {
        return disableUrlEncodingForBoundRequests;
    }

    @Override
    public boolean isUseLaxCookieEncoder() {
        return useLaxCookieEncoder;
    }

    @Override
    public boolean isDisableZeroCopy() {
        return disableZeroCopy;
    }

    @Override
    public boolean isKeepEncodingHeader() {
        return keepEncodingHeader;
    }

    @Override
    public ProxyServerSelector getProxyServerSelector() {
        return proxyServerSelector;
    }

    // websocket
    @Override
    public boolean isAggregateWebSocketFrameFragments() {
        return aggregateWebSocketFrameFragments;
    }

    @Override
    public boolean isEnableWebSocketCompression() {
        return enablewebSocketCompression;
    }

    @Override
    public int getWebSocketMaxBufferSize() {
        return webSocketMaxBufferSize;
    }

    @Override
    public int getWebSocketMaxFrameSize() {
        return webSocketMaxFrameSize;
    }

    // timeouts
    @Override
    public Duration getConnectTimeout() {
        return connectTimeout;
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
    public Duration getShutdownQuietPeriod() {
        return shutdownQuietPeriod;
    }

    @Override
    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    // keep-alive
    @Override
    public boolean isKeepAlive() {
        return keepAlive;
    }

    @Override
    public Duration getPooledConnectionIdleTimeout() {
        return pooledConnectionIdleTimeout;
    }

    @Override
    public Duration getConnectionPoolCleanerPeriod() {
        return connectionPoolCleanerPeriod;
    }

    @Override
    public Duration getConnectionTtl() {
        return connectionTtl;
    }

    @Override
    public int getMaxConnections() {
        return maxConnections;
    }

    @Override
    public int getMaxConnectionsPerHost() {
        return maxConnectionsPerHost;
    }

    @Override
    public int getAcquireFreeChannelTimeout() {
        return acquireFreeChannelTimeout;
    }

    @Override
    public @Nullable ChannelPool getChannelPool() {
        return channelPool;
    }

    @Override
    public @Nullable ConnectionSemaphoreFactory getConnectionSemaphoreFactory() {
        return connectionSemaphoreFactory;
    }

    @Override
    public KeepAliveStrategy getKeepAliveStrategy() {
        return keepAliveStrategy;
    }

    @Override
    public boolean isValidateResponseHeaders() {
        return validateResponseHeaders;
    }

    @Override
    public boolean isStripAuthorizationOnRedirect() {
        return stripAuthorizationOnRedirect;
    }

    // ssl
    @Override
    public boolean isUseOpenSsl() {
        return useOpenSsl;
    }

    @Override
    public boolean isUseInsecureTrustManager() {
        return useInsecureTrustManager;
    }

    @Override
    public boolean isDisableHttpsEndpointIdentificationAlgorithm() {
        return disableHttpsEndpointIdentificationAlgorithm;
    }

    @Override
    public int getHandshakeTimeout() {
        return handshakeTimeout;
    }

    @Override
    public @Nullable String[] getEnabledProtocols() {
        return enabledProtocols;
    }

    @Override
    public @Nullable String[] getEnabledCipherSuites() {
        return enabledCipherSuites;
    }

    @Override
    public boolean isFilterInsecureCipherSuites() {
        return filterInsecureCipherSuites;
    }

    @Override
    public int getSslSessionCacheSize() {
        return sslSessionCacheSize;
    }

    @Override
    public int getSslSessionTimeout() {
        return sslSessionTimeout;
    }

    @Override
    public @Nullable SslContext getSslContext() {
        return sslContext;
    }

    @Override
    public @Nullable SslEngineFactory getSslEngineFactory() {
        return sslEngineFactory;
    }

    // filters
    @Override
    public List<RequestFilter> getRequestFilters() {
        return requestFilters;
    }

    @Override
    public List<ResponseFilter> getResponseFilters() {
        return responseFilters;
    }

    @Override
    public List<IOExceptionFilter> getIoExceptionFilters() {
        return ioExceptionFilters;
    }

    // cookie store
    @Override
    public CookieStore getCookieStore() {
        return cookieStore;
    }

    @Override
    public int expiredCookieEvictionDelay() {
        return expiredCookieEvictionDelay;
    }

    // tuning
    @Override
    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }

    @Override
    public boolean isSoReuseAddress() {
        return soReuseAddress;
    }

    @Override
    public boolean isSoKeepAlive() {
        return soKeepAlive;
    }

    @Override
    public int getSoLinger() {
        return soLinger;
    }

    @Override
    public int getSoSndBuf() {
        return soSndBuf;
    }

    @Override
    public int getSoRcvBuf() {
        return soRcvBuf;
    }

    // internals
    @Override
    public String getThreadPoolName() {
        return threadPoolName;
    }

    @Override
    public int getHttpClientCodecMaxInitialLineLength() {
        return httpClientCodecMaxInitialLineLength;
    }

    @Override
    public int getHttpClientCodecMaxHeaderSize() {
        return httpClientCodecMaxHeaderSize;
    }

    @Override
    public int getHttpClientCodecMaxChunkSize() {
        return httpClientCodecMaxChunkSize;
    }

    @Override
    public int getHttpClientCodecInitialBufferSize() {
        return httpClientCodecInitialBufferSize;
    }

    @Override
    public boolean getHttpClientCodecParseHttpAfterConnectRequest() {
        return httpClientCodecParseHttpAfterConnectRequest;
    }

    @Override
    public boolean getHttpClientCodecAllowDuplicateContentLengths() {
        return httpClientCodecAllowDuplicateContentLengths;
    }


    @Override
    public int getChunkedFileChunkSize() {
        return chunkedFileChunkSize;
    }

    @Override
    public Map<ChannelOption<Object>, Object> getChannelOptions() {
        return channelOptions;
    }

    @Override
    public @Nullable EventLoopGroup getEventLoopGroup() {
        return eventLoopGroup;
    }

    @Override
    public boolean isUseNativeTransport() {
        return useNativeTransport;
    }

    @Override
    public boolean isUseOnlyEpollNativeTransport() {
        return useOnlyEpollNativeTransport;
    }

    @Override
    public @Nullable ByteBufAllocator getAllocator() {
        return allocator;
    }

    @Override
    public @Nullable Timer getNettyTimer() {
        return nettyTimer;
    }

    @Override
    public long getHashedWheelTimerTickDuration() {
        return hashedWheelTimerTickDuration;
    }

    @Override
    public int getHashedWheelTimerSize() {
        return hashedWheelTimerSize;
    }

    @Override
    public @Nullable ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    @Override
    public @Nullable Consumer<Channel> getHttpAdditionalChannelInitializer() {
        return httpAdditionalChannelInitializer;
    }

    @Override
    public @Nullable Consumer<Channel> getWsAdditionalChannelInitializer() {
        return wsAdditionalChannelInitializer;
    }

    @Override
    public ResponseBodyPartFactory getResponseBodyPartFactory() {
        return responseBodyPartFactory;
    }

    @Override
    public int getIoThreadsCount() {
        return ioThreadsCount;
    }

    /**
     * Builder for an {@link AsyncHttpClient}
     */
    public static class Builder {

        // filters
        private final List<RequestFilter> requestFilters = new LinkedList<>();
        private final List<ResponseFilter> responseFilters = new LinkedList<>();
        private final List<IOExceptionFilter> ioExceptionFilters = new LinkedList<>();
        // http
        private boolean followRedirect = defaultFollowRedirect();
        private int maxRedirects = defaultMaxRedirects();
        private boolean strict302Handling = defaultStrict302Handling();
        private boolean compressionEnforced = defaultCompressionEnforced();
        private boolean enableAutomaticDecompression = defaultEnableAutomaticDecompression();
        private String userAgent = defaultUserAgent();
        private @Nullable Realm realm;
        private int maxRequestRetry = defaultMaxRequestRetry();
        private boolean disableUrlEncodingForBoundRequests = defaultDisableUrlEncodingForBoundRequests();
        private boolean useLaxCookieEncoder = defaultUseLaxCookieEncoder();
        private boolean disableZeroCopy = defaultDisableZeroCopy();
        private boolean keepEncodingHeader = defaultKeepEncodingHeader();
        private @Nullable ProxyServerSelector proxyServerSelector;
        private boolean useProxySelector = defaultUseProxySelector();
        private boolean useProxyProperties = defaultUseProxyProperties();
        private boolean validateResponseHeaders = defaultValidateResponseHeaders();
        private boolean stripAuthorizationOnRedirect = false; // default value

        // websocket
        private boolean aggregateWebSocketFrameFragments = defaultAggregateWebSocketFrameFragments();
        private boolean enablewebSocketCompression = defaultEnableWebSocketCompression();
        private int webSocketMaxBufferSize = defaultWebSocketMaxBufferSize();
        private int webSocketMaxFrameSize = defaultWebSocketMaxFrameSize();

        // timeouts
        private Duration connectTimeout = defaultConnectTimeout();
        private Duration requestTimeout = defaultRequestTimeout();
        private Duration readTimeout = defaultReadTimeout();
        private Duration shutdownQuietPeriod = defaultShutdownQuietPeriod();
        private Duration shutdownTimeout = defaultShutdownTimeout();

        // keep-alive
        private boolean keepAlive = defaultKeepAlive();
        private Duration pooledConnectionIdleTimeout = defaultPooledConnectionIdleTimeout();
        private Duration connectionPoolCleanerPeriod = defaultConnectionPoolCleanerPeriod();
        private Duration connectionTtl = defaultConnectionTtl();
        private int maxConnections = defaultMaxConnections();
        private int maxConnectionsPerHost = defaultMaxConnectionsPerHost();
        private int acquireFreeChannelTimeout = defaultAcquireFreeChannelTimeout();
        private @Nullable ChannelPool channelPool;
        private @Nullable ConnectionSemaphoreFactory connectionSemaphoreFactory;
        private KeepAliveStrategy keepAliveStrategy = new DefaultKeepAliveStrategy();

        // ssl
        private boolean useOpenSsl = defaultUseOpenSsl();
        private boolean useInsecureTrustManager = defaultUseInsecureTrustManager();
        private boolean disableHttpsEndpointIdentificationAlgorithm = defaultDisableHttpsEndpointIdentificationAlgorithm();
        private int handshakeTimeout = defaultHandshakeTimeout();
        private @Nullable String[] enabledProtocols = defaultEnabledProtocols();
        private @Nullable String[] enabledCipherSuites = defaultEnabledCipherSuites();
        private boolean filterInsecureCipherSuites = defaultFilterInsecureCipherSuites();
        private int sslSessionCacheSize = defaultSslSessionCacheSize();
        private int sslSessionTimeout = defaultSslSessionTimeout();
        private @Nullable SslContext sslContext;
        private @Nullable SslEngineFactory sslEngineFactory;

        // cookie store
        private CookieStore cookieStore = new ThreadSafeCookieStore();
        private int expiredCookieEvictionDelay = defaultExpiredCookieEvictionDelay();

        // tuning
        private boolean tcpNoDelay = defaultTcpNoDelay();
        private boolean soReuseAddress = defaultSoReuseAddress();
        private boolean soKeepAlive = defaultSoKeepAlive();
        private int soLinger = defaultSoLinger();
        private int soSndBuf = defaultSoSndBuf();
        private int soRcvBuf = defaultSoRcvBuf();

        // internals
        private String threadPoolName = defaultThreadPoolName();
        private int httpClientCodecMaxInitialLineLength = defaultHttpClientCodecMaxInitialLineLength();
        private int httpClientCodecMaxHeaderSize = defaultHttpClientCodecMaxHeaderSize();
        private int httpClientCodecMaxChunkSize = defaultHttpClientCodecMaxChunkSize();
        private int httpClientCodecInitialBufferSize = defaultHttpClientCodecInitialBufferSize();
        private boolean httpClientCodecParseHttpAfterConnectRequest = defaultHttpClientCodecParseHttpAfterConnectRequest();
        private boolean httpClientCodecAllowDuplicateContentLengths = defaultHttpClientCodecAllowDuplicateContentLengths();
        private int chunkedFileChunkSize = defaultChunkedFileChunkSize();
        private boolean useNativeTransport = defaultUseNativeTransport();
        private boolean useOnlyEpollNativeTransport = defaultUseOnlyEpollNativeTransport();
        private @Nullable ByteBufAllocator allocator;
        private final Map<ChannelOption<Object>, Object> channelOptions = new HashMap<>();
        private @Nullable EventLoopGroup eventLoopGroup;
        private @Nullable Timer nettyTimer;
        private @Nullable ThreadFactory threadFactory;
        private @Nullable Consumer<Channel> httpAdditionalChannelInitializer;
        private @Nullable Consumer<Channel> wsAdditionalChannelInitializer;
        private ResponseBodyPartFactory responseBodyPartFactory = ResponseBodyPartFactory.EAGER;
        private int ioThreadsCount = defaultIoThreadsCount();
        private long hashedWheelTickDuration = defaultHashedWheelTimerTickDuration();
        private int hashedWheelSize = defaultHashedWheelTimerSize();

        public Builder() {
        }

        public Builder(AsyncHttpClientConfig config) {
            // http
            followRedirect = config.isFollowRedirect();
            maxRedirects = config.getMaxRedirects();
            strict302Handling = config.isStrict302Handling();
            compressionEnforced = config.isCompressionEnforced();
            enableAutomaticDecompression = config.isEnableAutomaticDecompression();
            userAgent = config.getUserAgent();
            realm = config.getRealm();
            maxRequestRetry = config.getMaxRequestRetry();
            disableUrlEncodingForBoundRequests = config.isDisableUrlEncodingForBoundRequests();
            useLaxCookieEncoder = config.isUseLaxCookieEncoder();
            disableZeroCopy = config.isDisableZeroCopy();
            keepEncodingHeader = config.isKeepEncodingHeader();
            proxyServerSelector = config.getProxyServerSelector();
            validateResponseHeaders = config.isValidateResponseHeaders();
            stripAuthorizationOnRedirect = config.isStripAuthorizationOnRedirect();

            // websocket
            aggregateWebSocketFrameFragments = config.isAggregateWebSocketFrameFragments();
            enablewebSocketCompression = config.isEnableWebSocketCompression();
            webSocketMaxBufferSize = config.getWebSocketMaxBufferSize();
            webSocketMaxFrameSize = config.getWebSocketMaxFrameSize();

            // timeouts
            connectTimeout = config.getConnectTimeout();
            requestTimeout = config.getRequestTimeout();
            readTimeout = config.getReadTimeout();
            shutdownQuietPeriod = config.getShutdownQuietPeriod();
            shutdownTimeout = config.getShutdownTimeout();

            // keep-alive
            keepAlive = config.isKeepAlive();
            pooledConnectionIdleTimeout = config.getPooledConnectionIdleTimeout();
            connectionPoolCleanerPeriod = config.getConnectionPoolCleanerPeriod();
            connectionTtl = config.getConnectionTtl();
            maxConnections = config.getMaxConnections();
            maxConnectionsPerHost = config.getMaxConnectionsPerHost();
            channelPool = config.getChannelPool();
            connectionSemaphoreFactory = config.getConnectionSemaphoreFactory();
            keepAliveStrategy = config.getKeepAliveStrategy();
            acquireFreeChannelTimeout = config.getAcquireFreeChannelTimeout();

            // ssl
            useOpenSsl = config.isUseOpenSsl();
            useInsecureTrustManager = config.isUseInsecureTrustManager();
            disableHttpsEndpointIdentificationAlgorithm = config.isDisableHttpsEndpointIdentificationAlgorithm();
            handshakeTimeout = config.getHandshakeTimeout();
            enabledProtocols = config.getEnabledProtocols();
            enabledCipherSuites = config.getEnabledCipherSuites();
            filterInsecureCipherSuites = config.isFilterInsecureCipherSuites();
            sslSessionCacheSize = config.getSslSessionCacheSize();
            sslSessionTimeout = config.getSslSessionTimeout();
            sslContext = config.getSslContext();
            sslEngineFactory = config.getSslEngineFactory();

            // filters
            requestFilters.addAll(config.getRequestFilters());
            responseFilters.addAll(config.getResponseFilters());
            ioExceptionFilters.addAll(config.getIoExceptionFilters());

            // cookie store
            cookieStore = config.getCookieStore();
            expiredCookieEvictionDelay = config.expiredCookieEvictionDelay();

            // tuning
            tcpNoDelay = config.isTcpNoDelay();
            soReuseAddress = config.isSoReuseAddress();
            soKeepAlive = config.isSoKeepAlive();
            soLinger = config.getSoLinger();
            soSndBuf = config.getSoSndBuf();
            soRcvBuf = config.getSoRcvBuf();

            // internals
            threadPoolName = config.getThreadPoolName();
            httpClientCodecMaxInitialLineLength = config.getHttpClientCodecMaxInitialLineLength();
            httpClientCodecMaxHeaderSize = config.getHttpClientCodecMaxHeaderSize();
            httpClientCodecMaxChunkSize = config.getHttpClientCodecMaxChunkSize();
            httpClientCodecInitialBufferSize = config.getHttpClientCodecInitialBufferSize();
            chunkedFileChunkSize = config.getChunkedFileChunkSize();
            channelOptions.putAll(config.getChannelOptions());
            eventLoopGroup = config.getEventLoopGroup();
            useNativeTransport = config.isUseNativeTransport();
            useOnlyEpollNativeTransport = config.isUseOnlyEpollNativeTransport();

            allocator = config.getAllocator();
            nettyTimer = config.getNettyTimer();
            threadFactory = config.getThreadFactory();
            httpAdditionalChannelInitializer = config.getHttpAdditionalChannelInitializer();
            wsAdditionalChannelInitializer = config.getWsAdditionalChannelInitializer();
            responseBodyPartFactory = config.getResponseBodyPartFactory();
            ioThreadsCount = config.getIoThreadsCount();
            hashedWheelTickDuration = config.getHashedWheelTimerTickDuration();
            hashedWheelSize = config.getHashedWheelTimerSize();
        }

        // http
        public Builder setFollowRedirect(boolean followRedirect) {
            this.followRedirect = followRedirect;
            return this;
        }

        public Builder setMaxRedirects(int maxRedirects) {
            this.maxRedirects = maxRedirects;
            return this;
        }

        public Builder setStrict302Handling(final boolean strict302Handling) {
            this.strict302Handling = strict302Handling;
            return this;
        }

        /**
         * If true, AHC will  add Accept-Encoding HTTP header to each request
         * <p>
         * If false (default), AHC will either leave AcceptEncoding header as is
         * (if enableAutomaticDecompression is false) or will remove unsupported
         * algorithms (if enableAutomaticDecompression is true)
         */
        public Builder setCompressionEnforced(boolean compressionEnforced) {
            this.compressionEnforced = compressionEnforced;
            return this;
        }

        /*
         * If true (default), AHC will add a Netty HttpContentDecompressor, so compressed
         * content will automatically get decompressed.
         *
         * If set to false, response will be delivered as is received. Decompression must
         * be done by calling code.
         */
        public Builder setEnableAutomaticDecompression(boolean enable) {
            enableAutomaticDecompression = enable;
            return this;
        }

        public Builder setUserAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder setRealm(Realm realm) {
            this.realm = realm;
            return this;
        }

        public Builder setRealm(Realm.Builder realmBuilder) {
            realm = realmBuilder.build();
            return this;
        }

        public Builder setMaxRequestRetry(int maxRequestRetry) {
            this.maxRequestRetry = maxRequestRetry;
            return this;
        }

        public Builder setDisableUrlEncodingForBoundRequests(boolean disableUrlEncodingForBoundRequests) {
            this.disableUrlEncodingForBoundRequests = disableUrlEncodingForBoundRequests;
            return this;
        }

        public Builder setUseLaxCookieEncoder(boolean useLaxCookieEncoder) {
            this.useLaxCookieEncoder = useLaxCookieEncoder;
            return this;
        }

        public Builder setDisableZeroCopy(boolean disableZeroCopy) {
            this.disableZeroCopy = disableZeroCopy;
            return this;
        }

        public Builder setKeepEncodingHeader(boolean keepEncodingHeader) {
            this.keepEncodingHeader = keepEncodingHeader;
            return this;
        }

        public Builder setProxyServerSelector(ProxyServerSelector proxyServerSelector) {
            this.proxyServerSelector = proxyServerSelector;
            return this;
        }

        public Builder setValidateResponseHeaders(boolean validateResponseHeaders) {
            this.validateResponseHeaders = validateResponseHeaders;
            return this;
        }

        public Builder setProxyServer(ProxyServer proxyServer) {
            proxyServerSelector = uri -> proxyServer;
            return this;
        }

        public Builder setProxyServer(ProxyServer.Builder proxyServerBuilder) {
            return setProxyServer(proxyServerBuilder.build());
        }

        public Builder setUseProxySelector(boolean useProxySelector) {
            this.useProxySelector = useProxySelector;
            return this;
        }

        public Builder setUseProxyProperties(boolean useProxyProperties) {
            this.useProxyProperties = useProxyProperties;
            return this;
        }

        public Builder setStripAuthorizationOnRedirect(boolean value) {
            stripAuthorizationOnRedirect = value;
            return this;
        }

        // websocket
        public Builder setAggregateWebSocketFrameFragments(boolean aggregateWebSocketFrameFragments) {
            this.aggregateWebSocketFrameFragments = aggregateWebSocketFrameFragments;
            return this;
        }

        public Builder setEnablewebSocketCompression(boolean enablewebSocketCompression) {
            this.enablewebSocketCompression = enablewebSocketCompression;
            return this;
        }

        public Builder setWebSocketMaxBufferSize(int webSocketMaxBufferSize) {
            this.webSocketMaxBufferSize = webSocketMaxBufferSize;
            return this;
        }

        public Builder setWebSocketMaxFrameSize(int webSocketMaxFrameSize) {
            this.webSocketMaxFrameSize = webSocketMaxFrameSize;
            return this;
        }

        // timeouts
        public Builder setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public Builder setShutdownQuietPeriod(Duration shutdownQuietPeriod) {
            this.shutdownQuietPeriod = shutdownQuietPeriod;
            return this;
        }

        public Builder setShutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        // keep-alive
        public Builder setKeepAlive(boolean keepAlive) {
            this.keepAlive = keepAlive;
            return this;
        }

        public Builder setPooledConnectionIdleTimeout(Duration pooledConnectionIdleTimeout) {
            this.pooledConnectionIdleTimeout = pooledConnectionIdleTimeout;
            return this;
        }

        public Builder setConnectionPoolCleanerPeriod(Duration connectionPoolCleanerPeriod) {
            this.connectionPoolCleanerPeriod = connectionPoolCleanerPeriod;
            return this;
        }

        public Builder setConnectionTtl(Duration connectionTtl) {
            this.connectionTtl = connectionTtl;
            return this;
        }

        public Builder setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
            return this;
        }

        public Builder setMaxConnectionsPerHost(int maxConnectionsPerHost) {
            this.maxConnectionsPerHost = maxConnectionsPerHost;
            return this;
        }

        /**
         * Sets the maximum duration in milliseconds to acquire a free channel to send a request
         *
         * @param acquireFreeChannelTimeout maximum duration in milliseconds to acquire a free channel to send a request
         * @return the same builder instance
         */
        public Builder setAcquireFreeChannelTimeout(int acquireFreeChannelTimeout) {
            this.acquireFreeChannelTimeout = acquireFreeChannelTimeout;
            return this;
        }

        public Builder setChannelPool(ChannelPool channelPool) {
            this.channelPool = channelPool;
            return this;
        }

        public Builder setConnectionSemaphoreFactory(ConnectionSemaphoreFactory connectionSemaphoreFactory) {
            this.connectionSemaphoreFactory = connectionSemaphoreFactory;
            return this;
        }

        public Builder setKeepAliveStrategy(KeepAliveStrategy keepAliveStrategy) {
            this.keepAliveStrategy = keepAliveStrategy;
            return this;
        }

        // ssl
        public Builder setUseOpenSsl(boolean useOpenSsl) {
            this.useOpenSsl = useOpenSsl;
            return this;
        }

        public Builder setUseInsecureTrustManager(boolean useInsecureTrustManager) {
            this.useInsecureTrustManager = useInsecureTrustManager;
            return this;
        }

        public Builder setDisableHttpsEndpointIdentificationAlgorithm(boolean disableHttpsEndpointIdentificationAlgorithm) {
            this.disableHttpsEndpointIdentificationAlgorithm = disableHttpsEndpointIdentificationAlgorithm;
            return this;
        }

        public Builder setHandshakeTimeout(int handshakeTimeout) {
            this.handshakeTimeout = handshakeTimeout;
            return this;
        }

        public Builder setEnabledProtocols(String[] enabledProtocols) {
            this.enabledProtocols = enabledProtocols;
            return this;
        }

        public Builder setEnabledCipherSuites(String[] enabledCipherSuites) {
            this.enabledCipherSuites = enabledCipherSuites;
            return this;
        }

        public Builder setFilterInsecureCipherSuites(boolean filterInsecureCipherSuites) {
            this.filterInsecureCipherSuites = filterInsecureCipherSuites;
            return this;
        }

        public Builder setSslSessionCacheSize(Integer sslSessionCacheSize) {
            this.sslSessionCacheSize = sslSessionCacheSize;
            return this;
        }

        public Builder setSslSessionTimeout(Integer sslSessionTimeout) {
            this.sslSessionTimeout = sslSessionTimeout;
            return this;
        }

        public Builder setSslContext(final SslContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        public Builder setSslEngineFactory(SslEngineFactory sslEngineFactory) {
            this.sslEngineFactory = sslEngineFactory;
            return this;
        }

        // filters
        public Builder addRequestFilter(RequestFilter requestFilter) {
            requestFilters.add(requestFilter);
            return this;
        }

        public Builder removeRequestFilter(RequestFilter requestFilter) {
            requestFilters.remove(requestFilter);
            return this;
        }

        public Builder addResponseFilter(ResponseFilter responseFilter) {
            responseFilters.add(responseFilter);
            return this;
        }

        public Builder removeResponseFilter(ResponseFilter responseFilter) {
            responseFilters.remove(responseFilter);
            return this;
        }

        public Builder addIOExceptionFilter(IOExceptionFilter ioExceptionFilter) {
            ioExceptionFilters.add(ioExceptionFilter);
            return this;
        }

        public Builder removeIOExceptionFilter(IOExceptionFilter ioExceptionFilter) {
            ioExceptionFilters.remove(ioExceptionFilter);
            return this;
        }

        // cookie store
        public Builder setCookieStore(CookieStore cookieStore) {
            this.cookieStore = cookieStore;
            return this;
        }

        public Builder setExpiredCookieEvictionDelay(int expiredCookieEvictionDelay) {
            this.expiredCookieEvictionDelay = expiredCookieEvictionDelay;
            return this;
        }

        // tuning
        public Builder setTcpNoDelay(boolean tcpNoDelay) {
            this.tcpNoDelay = tcpNoDelay;
            return this;
        }

        public Builder setSoReuseAddress(boolean soReuseAddress) {
            this.soReuseAddress = soReuseAddress;
            return this;
        }

        public Builder setSoKeepAlive(boolean soKeepAlive) {
            this.soKeepAlive = soKeepAlive;
            return this;
        }

        public Builder setSoLinger(int soLinger) {
            this.soLinger = soLinger;
            return this;
        }

        public Builder setSoSndBuf(int soSndBuf) {
            this.soSndBuf = soSndBuf;
            return this;
        }

        public Builder setSoRcvBuf(int soRcvBuf) {
            this.soRcvBuf = soRcvBuf;
            return this;
        }

        // internals
        public Builder setThreadPoolName(String threadPoolName) {
            this.threadPoolName = threadPoolName;
            return this;
        }

        public Builder setHttpClientCodecMaxInitialLineLength(int httpClientCodecMaxInitialLineLength) {
            this.httpClientCodecMaxInitialLineLength = httpClientCodecMaxInitialLineLength;
            return this;
        }

        public Builder setHttpClientCodecMaxHeaderSize(int httpClientCodecMaxHeaderSize) {
            this.httpClientCodecMaxHeaderSize = httpClientCodecMaxHeaderSize;
            return this;
        }

        public Builder setHttpClientCodecMaxChunkSize(int httpClientCodecMaxChunkSize) {
            this.httpClientCodecMaxChunkSize = httpClientCodecMaxChunkSize;
            return this;
        }

        public Builder setHttpClientCodecInitialBufferSize(int httpClientCodecInitialBufferSize) {
            this.httpClientCodecInitialBufferSize = httpClientCodecInitialBufferSize;
            return this;
        }

        public Builder setHttpClientCodecParseHttpAfterConnectRequest(boolean httpClientCodecParseHttpAfterConnectRequest) {
            this.httpClientCodecParseHttpAfterConnectRequest = httpClientCodecParseHttpAfterConnectRequest;
            return this;
        }

        public Builder setHttpClientCodecAllowDuplicateContentLengths(boolean httpClientCodecAllowDuplicateContentLengths) {
            this.httpClientCodecAllowDuplicateContentLengths = httpClientCodecAllowDuplicateContentLengths;
            return this;
        }

        public Builder setChunkedFileChunkSize(int chunkedFileChunkSize) {
            this.chunkedFileChunkSize = chunkedFileChunkSize;
            return this;
        }

        public Builder setHashedWheelTickDuration(long hashedWheelTickDuration) {
            this.hashedWheelTickDuration = hashedWheelTickDuration;
            return this;
        }

        public Builder setHashedWheelSize(int hashedWheelSize) {
            this.hashedWheelSize = hashedWheelSize;
            return this;
        }

        @SuppressWarnings("unchecked")
        public <T> Builder addChannelOption(ChannelOption<T> name, T value) {
            channelOptions.put((ChannelOption<Object>) name, value);
            return this;
        }

        public Builder setEventLoopGroup(EventLoopGroup eventLoopGroup) {
            this.eventLoopGroup = eventLoopGroup;
            return this;
        }

        public Builder setUseNativeTransport(boolean useNativeTransport) {
            this.useNativeTransport = useNativeTransport;
            return this;
        }

        public Builder setUseOnlyEpollNativeTransport(boolean useOnlyEpollNativeTransport) {
            this.useOnlyEpollNativeTransport = useOnlyEpollNativeTransport;
            return this;
        }

        public Builder setAllocator(ByteBufAllocator allocator) {
            this.allocator = allocator;
            return this;
        }

        public Builder setNettyTimer(Timer nettyTimer) {
            this.nettyTimer = nettyTimer;
            return this;
        }

        public Builder setThreadFactory(ThreadFactory threadFactory) {
            this.threadFactory = threadFactory;
            return this;
        }

        public Builder setHttpAdditionalChannelInitializer(Consumer<Channel> httpAdditionalChannelInitializer) {
            this.httpAdditionalChannelInitializer = httpAdditionalChannelInitializer;
            return this;
        }

        public Builder setWsAdditionalChannelInitializer(Consumer<Channel> wsAdditionalChannelInitializer) {
            this.wsAdditionalChannelInitializer = wsAdditionalChannelInitializer;
            return this;
        }

        public Builder setResponseBodyPartFactory(ResponseBodyPartFactory responseBodyPartFactory) {
            this.responseBodyPartFactory = responseBodyPartFactory;
            return this;
        }

        public Builder setIoThreadsCount(int ioThreadsCount) {
            this.ioThreadsCount = ioThreadsCount;
            return this;
        }

        private ProxyServerSelector resolveProxyServerSelector() {
            if (proxyServerSelector != null) {
                return proxyServerSelector;
            }

            if (useProxySelector) {
                return ProxyUtils.getJdkDefaultProxyServerSelector();
            }

            if (useProxyProperties) {
                return ProxyUtils.createProxyServerSelector(System.getProperties());
            }

            return ProxyServerSelector.NO_PROXY_SELECTOR;
        }

        public DefaultAsyncHttpClientConfig build() {

            return new DefaultAsyncHttpClientConfig(
                    followRedirect,
                    maxRedirects,
                    strict302Handling,
                    compressionEnforced,
                    enableAutomaticDecompression,
                    userAgent,
                    realm,
                    maxRequestRetry,
                    disableUrlEncodingForBoundRequests,
                    useLaxCookieEncoder,
                    disableZeroCopy,
                    keepEncodingHeader,
                    resolveProxyServerSelector(),
                    validateResponseHeaders,
                    aggregateWebSocketFrameFragments,
                    enablewebSocketCompression,
                    stripAuthorizationOnRedirect,
                    connectTimeout,
                    requestTimeout,
                    readTimeout,
                    shutdownQuietPeriod,
                    shutdownTimeout,
                    keepAlive,
                    pooledConnectionIdleTimeout,
                    connectionPoolCleanerPeriod,
                    connectionTtl,
                    maxConnections,
                    maxConnectionsPerHost,
                    acquireFreeChannelTimeout,
                    channelPool,
                    connectionSemaphoreFactory,
                    keepAliveStrategy,
                    useOpenSsl,
                    useInsecureTrustManager,
                    disableHttpsEndpointIdentificationAlgorithm,
                    handshakeTimeout,
                    enabledProtocols,
                    enabledCipherSuites,
                    filterInsecureCipherSuites,
                    sslSessionCacheSize,
                    sslSessionTimeout,
                    sslContext,
                    sslEngineFactory,
                    requestFilters.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(requestFilters),
                    responseFilters.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(responseFilters),
                    ioExceptionFilters.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(ioExceptionFilters),
                    cookieStore,
                    expiredCookieEvictionDelay,
                    tcpNoDelay,
                    soReuseAddress,
                    soKeepAlive,
                    soLinger,
                    soSndBuf,
                    soRcvBuf,
                    threadPoolName,
                    httpClientCodecMaxInitialLineLength,
                    httpClientCodecMaxHeaderSize,
                    httpClientCodecMaxChunkSize,
                    httpClientCodecInitialBufferSize,
                    httpClientCodecParseHttpAfterConnectRequest,
                    httpClientCodecAllowDuplicateContentLengths,
                    chunkedFileChunkSize,
                    webSocketMaxBufferSize,
                    webSocketMaxFrameSize,
                    channelOptions.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(channelOptions),
                    eventLoopGroup,
                    useNativeTransport,
                    useOnlyEpollNativeTransport,
                    allocator,
                    nettyTimer,
                    threadFactory,
                    httpAdditionalChannelInitializer,
                    wsAdditionalChannelInitializer,
                    responseBodyPartFactory,
                    ioThreadsCount,
                    hashedWheelTickDuration,
                    hashedWheelSize);
        }
    }
}
