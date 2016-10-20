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

import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.*;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.Timer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadFactory;

import org.asynchttpclient.channel.ChannelPool;
import org.asynchttpclient.channel.DefaultKeepAliveStrategy;
import org.asynchttpclient.channel.KeepAliveStrategy;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyServerSelector;
import org.asynchttpclient.util.ProxyUtils;

/**
 * Configuration class to use with a {@link AsyncHttpClient}. System property can be also used to configure this object default behavior by doing: <br>
 * -Dorg.asynchttpclient.nameOfTheProperty
 * 
 * @see AsyncHttpClientConfig for documentation
 */
public class DefaultAsyncHttpClientConfig implements AsyncHttpClientConfig {

    private static final String AHC_VERSION;

    static {
        try (InputStream is = DefaultAsyncHttpClientConfig.class.getResourceAsStream("/ahc-version.properties")) {
            Properties prop = new Properties();
            prop.load(is);
            AHC_VERSION = prop.getProperty("ahc.version", "UNKNOWN");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // http
    private final boolean followRedirect;
    private final int maxRedirects;
    private final boolean strict302Handling;
    private final boolean compressionEnforced;
    private final String userAgent;
    private final Realm realm;
    private final int maxRequestRetry;
    private final boolean disableUrlEncodingForBoundRequests;
    private final boolean disableZeroCopy;
    private final boolean keepEncodingHeader;
    private final ProxyServerSelector proxyServerSelector;
    private final boolean validateResponseHeaders;

    // timeouts
    private final int connectTimeout;
    private final int requestTimeout;
    private final int readTimeout;
    private final int shutdownQuietPeriod;
    private final int shutdownTimeout;

    // keep-alive
    private final boolean keepAlive;
    private final int pooledConnectionIdleTimeout;
    private final int connectionPoolCleanerPeriod;
    private final int connectionTtl;
    private final int maxConnections;
    private final int maxConnectionsPerHost;
    private final ChannelPool channelPool;
    private final KeepAliveStrategy keepAliveStrategy;

    // ssl
    private final boolean useOpenSsl;
    private final boolean acceptAnyCertificate;
    private final int handshakeTimeout;
    private final String[] enabledProtocols;
    private final String[] enabledCipherSuites;
    private final int sslSessionCacheSize;
    private final int sslSessionTimeout;
    private final SslContext sslContext;
    private final SslEngineFactory sslEngineFactory;

    // filters
    private final List<RequestFilter> requestFilters;
    private final List<ResponseFilter> responseFilters;
    private final List<IOExceptionFilter> ioExceptionFilters;

    // internals
    private final String threadPoolName;
    private final int httpClientCodecMaxInitialLineLength;
    private final int httpClientCodecMaxHeaderSize;
    private final int httpClientCodecMaxChunkSize;
    private final int chunkedFileChunkSize;
    private final int webSocketMaxBufferSize;
    private final int webSocketMaxFrameSize;
    private final Map<ChannelOption<Object>, Object> channelOptions;
    private final EventLoopGroup eventLoopGroup;
    private final boolean useNativeTransport;
    private final ByteBufAllocator allocator;
    private final boolean tcpNoDelay;
    private final boolean soReuseAddress;
    private final int soLinger;
    private final int soSndBuf;
    private final int soRcvBuf;
    private final Timer nettyTimer;
    private final ThreadFactory threadFactory;
    private final AdditionalChannelInitializer httpAdditionalChannelInitializer;
    private final AdditionalChannelInitializer wsAdditionalChannelInitializer;
    private final ResponseBodyPartFactory responseBodyPartFactory;
    private final int ioThreadsCount;

    private DefaultAsyncHttpClientConfig(//
            // http
            boolean followRedirect,//
            int maxRedirects,//
            boolean strict302Handling,//
            boolean compressionEnforced,//
            String userAgent,//
            Realm realm,//
            int maxRequestRetry,//
            boolean disableUrlEncodingForBoundRequests,//
            boolean disableZeroCopy,//
            boolean keepEncodingHeader,//
            ProxyServerSelector proxyServerSelector,//
            boolean validateResponseHeaders,//

            // timeouts
            int connectTimeout,//
            int requestTimeout,//
            int readTimeout,//
            int shutdownQuietPeriod,//
            int shutdownTimeout,//

            // keep-alive
            boolean keepAlive,//
            int pooledConnectionIdleTimeout,//
            int connectionPoolCleanerPeriod,//
            int connectionTtl,//
            int maxConnections,//
            int maxConnectionsPerHost,//
            ChannelPool channelPool,//
            KeepAliveStrategy keepAliveStrategy,//

            // ssl
            boolean useOpenSsl,//
            boolean acceptAnyCertificate,//
            int handshakeTimeout,//
            String[] enabledProtocols,//
            String[] enabledCipherSuites,//
            int sslSessionCacheSize,//
            int sslSessionTimeout,//
            SslContext sslContext,//
            SslEngineFactory sslEngineFactory,//

            // filters
            List<RequestFilter> requestFilters,//
            List<ResponseFilter> responseFilters,//
            List<IOExceptionFilter> ioExceptionFilters,//

            // tuning
            boolean tcpNoDelay,//
            boolean soReuseAddress,//
            int soLinger, //
            int soSndBuf, //
            int soRcvBuf, //

            // internals
            String threadPoolName,//
            int httpClientCodecMaxInitialLineLength,//
            int httpClientCodecMaxHeaderSize,//
            int httpClientCodecMaxChunkSize,//
            int chunkedFileChunkSize,//
            int webSocketMaxBufferSize,//
            int webSocketMaxFrameSize,//
            Map<ChannelOption<Object>, Object> channelOptions,//
            EventLoopGroup eventLoopGroup,//
            boolean useNativeTransport,//
            ByteBufAllocator allocator,//
            Timer nettyTimer,//
            ThreadFactory threadFactory,//
            AdditionalChannelInitializer httpAdditionalChannelInitializer,//
            AdditionalChannelInitializer wsAdditionalChannelInitializer,//
            ResponseBodyPartFactory responseBodyPartFactory,//
            int ioThreadsCount) {

        // http
        this.followRedirect = followRedirect;
        this.maxRedirects = maxRedirects;
        this.strict302Handling = strict302Handling;
        this.compressionEnforced = compressionEnforced;
        this.userAgent = userAgent;
        this.realm = realm;
        this.maxRequestRetry = maxRequestRetry;
        this.disableUrlEncodingForBoundRequests = disableUrlEncodingForBoundRequests;
        this.disableZeroCopy = disableZeroCopy;
        this.keepEncodingHeader = keepEncodingHeader;
        this.proxyServerSelector = proxyServerSelector;
        this.validateResponseHeaders = validateResponseHeaders;

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
        this.channelPool = channelPool;
        this.keepAliveStrategy = keepAliveStrategy;

        // ssl
        this.useOpenSsl = useOpenSsl;
        this.acceptAnyCertificate = acceptAnyCertificate;
        this.handshakeTimeout = handshakeTimeout;
        this.enabledProtocols = enabledProtocols;
        this.enabledCipherSuites = enabledCipherSuites;
        this.sslSessionCacheSize = sslSessionCacheSize;
        this.sslSessionTimeout = sslSessionTimeout;
        this.sslContext = sslContext;
        this.sslEngineFactory = sslEngineFactory;

        // filters
        this.requestFilters = requestFilters;
        this.responseFilters = responseFilters;
        this.ioExceptionFilters = ioExceptionFilters;

        // tuning
        this.tcpNoDelay = tcpNoDelay;
        this.soReuseAddress = soReuseAddress;
        this.soLinger = soLinger;
        this.soSndBuf = soSndBuf;
        this.soRcvBuf = soRcvBuf;

        // internals
        this.threadPoolName = threadPoolName;
        this.httpClientCodecMaxInitialLineLength = httpClientCodecMaxInitialLineLength;
        this.httpClientCodecMaxHeaderSize = httpClientCodecMaxHeaderSize;
        this.httpClientCodecMaxChunkSize = httpClientCodecMaxChunkSize;
        this.chunkedFileChunkSize = chunkedFileChunkSize;
        this.webSocketMaxBufferSize = webSocketMaxBufferSize;
        this.webSocketMaxFrameSize = webSocketMaxFrameSize;
        this.channelOptions = channelOptions;
        this.eventLoopGroup = eventLoopGroup;
        this.useNativeTransport = useNativeTransport;
        this.allocator = allocator;
        this.nettyTimer = nettyTimer;
        this.threadFactory = threadFactory;
        this.httpAdditionalChannelInitializer = httpAdditionalChannelInitializer;
        this.wsAdditionalChannelInitializer = wsAdditionalChannelInitializer;
        this.responseBodyPartFactory = responseBodyPartFactory;
        this.ioThreadsCount = ioThreadsCount;
    }

    @Override
    public String getAhcVersion() {
        return AHC_VERSION;
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
    public String getUserAgent() {
        return userAgent;
    }

    @Override
    public Realm getRealm() {
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

    // timeouts

    @Override
    public int getConnectTimeout() {
        return connectTimeout;
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
    public int getShutdownQuietPeriod() {
        return shutdownQuietPeriod;
    }

    @Override
    public int getShutdownTimeout() {
        return shutdownTimeout;
    }

    // keep-alive
    @Override
    public boolean isKeepAlive() {
        return keepAlive;
    }

    @Override
    public int getPooledConnectionIdleTimeout() {
        return pooledConnectionIdleTimeout;
    }

    @Override
    public int getConnectionPoolCleanerPeriod() {
        return connectionPoolCleanerPeriod;
    }

    @Override
    public int getConnectionTtl() {
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
    public ChannelPool getChannelPool() {
        return channelPool;
    }

    @Override
    public KeepAliveStrategy getKeepAliveStrategy() {
        return keepAliveStrategy;
    }

    @Override
    public boolean isValidateResponseHeaders() {
        return validateResponseHeaders;
    }

    // ssl
    @Override
    public boolean isUseOpenSsl() {
        return useOpenSsl;
    }

    @Override
    public boolean isAcceptAnyCertificate() {
        return acceptAnyCertificate;
    }

    @Override
    public int getHandshakeTimeout() {
        return handshakeTimeout;
    }

    @Override
    public String[] getEnabledProtocols() {
        return enabledProtocols;
    }

    @Override
    public String[] getEnabledCipherSuites() {
        return enabledCipherSuites;
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
    public SslContext getSslContext() {
        return sslContext;
    }

    @Override
    public SslEngineFactory getSslEngineFactory() {
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
    public int getChunkedFileChunkSize() {
        return chunkedFileChunkSize;
    }

    @Override
    public int getWebSocketMaxBufferSize() {
        return webSocketMaxBufferSize;
    }

    @Override
    public int getWebSocketMaxFrameSize() {
        return webSocketMaxFrameSize;
    }

    @Override
    public Map<ChannelOption<Object>, Object> getChannelOptions() {
        return channelOptions;
    }

    @Override
    public EventLoopGroup getEventLoopGroup() {
        return eventLoopGroup;
    }

    @Override
    public boolean isUseNativeTransport() {
        return useNativeTransport;
    }

    @Override
    public ByteBufAllocator getAllocator() {
        return allocator;
    }

    @Override
    public Timer getNettyTimer() {
        return nettyTimer;
    }

    @Override
    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    @Override
    public AdditionalChannelInitializer getHttpAdditionalChannelInitializer() {
        return httpAdditionalChannelInitializer;
    }

    @Override
    public AdditionalChannelInitializer getWsAdditionalChannelInitializer() {
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

        // http
        private boolean followRedirect = defaultFollowRedirect();
        private int maxRedirects = defaultMaxRedirects();
        private boolean strict302Handling = defaultStrict302Handling();
        private boolean compressionEnforced = defaultCompressionEnforced();
        private String userAgent = defaultUserAgent();
        private Realm realm;
        private int maxRequestRetry = defaultMaxRequestRetry();
        private boolean disableUrlEncodingForBoundRequests = defaultDisableUrlEncodingForBoundRequests();
        private boolean disableZeroCopy = defaultDisableZeroCopy();
        private boolean keepEncodingHeader = defaultKeepEncodingHeader();
        private ProxyServerSelector proxyServerSelector;
        private boolean useProxySelector = defaultUseProxySelector();
        private boolean useProxyProperties = defaultUseProxyProperties();
        private boolean validateResponseHeaders = defaultValidateResponseHeaders();

        // timeouts
        private int connectTimeout = defaultConnectTimeout();
        private int requestTimeout = defaultRequestTimeout();
        private int readTimeout = defaultReadTimeout();
        private int shutdownQuietPeriod = defaultShutdownQuietPeriod();
        private int shutdownTimeout = defaultShutdownTimeout();

        // keep-alive
        private boolean keepAlive = defaultKeepAlive();
        private int pooledConnectionIdleTimeout = defaultPooledConnectionIdleTimeout();
        private int connectionPoolCleanerPeriod = defaultConnectionPoolCleanerPeriod();
        private int connectionTtl = defaultConnectionTtl();
        private int maxConnections = defaultMaxConnections();
        private int maxConnectionsPerHost = defaultMaxConnectionsPerHost();
        private ChannelPool channelPool;
        private KeepAliveStrategy keepAliveStrategy = new DefaultKeepAliveStrategy();

        // ssl
        private boolean useOpenSsl = defaultUseOpenSsl();
        private boolean acceptAnyCertificate = defaultAcceptAnyCertificate();
        private int handshakeTimeout = defaultHandshakeTimeout();
        private String[] enabledProtocols = defaultEnabledProtocols();
        private String[] enabledCipherSuites = defaultEnabledCipherSuites();
        private int sslSessionCacheSize = defaultSslSessionCacheSize();
        private int sslSessionTimeout = defaultSslSessionTimeout();
        private SslContext sslContext;
        private SslEngineFactory sslEngineFactory;

        // filters
        private final List<RequestFilter> requestFilters = new LinkedList<>();
        private final List<ResponseFilter> responseFilters = new LinkedList<>();
        private final List<IOExceptionFilter> ioExceptionFilters = new LinkedList<>();

        // tuning
        private boolean tcpNoDelay = defaultTcpNoDelay();
        private boolean soReuseAddress = defaultSoReuseAddress();
        private int soLinger = defaultSoLinger();
        private int soSndBuf = defaultSoSndBuf();
        private int soRcvBuf = defaultSoRcvBuf();

        // internals
        private String threadPoolName = defaultThreadPoolName();
        private int httpClientCodecMaxInitialLineLength = defaultHttpClientCodecMaxInitialLineLength();
        private int httpClientCodecMaxHeaderSize = defaultHttpClientCodecMaxHeaderSize();
        private int httpClientCodecMaxChunkSize = defaultHttpClientCodecMaxChunkSize();
        private int chunkedFileChunkSize = defaultChunkedFileChunkSize();
        private int webSocketMaxBufferSize = defaultWebSocketMaxBufferSize();
        private int webSocketMaxFrameSize = defaultWebSocketMaxFrameSize();
        private boolean useNativeTransport = defaultUseNativeTransport();
        private ByteBufAllocator allocator;
        private Map<ChannelOption<Object>, Object> channelOptions = new HashMap<>();
        private EventLoopGroup eventLoopGroup;
        private Timer nettyTimer;
        private ThreadFactory threadFactory;
        private AdditionalChannelInitializer httpAdditionalChannelInitializer;
        private AdditionalChannelInitializer wsAdditionalChannelInitializer;
        private ResponseBodyPartFactory responseBodyPartFactory = ResponseBodyPartFactory.EAGER;
        private int ioThreadsCount = defaultIoThreadsCount();

        public Builder() {
        }

        public Builder(AsyncHttpClientConfig config) {
            // http
            followRedirect = config.isFollowRedirect();
            maxRedirects = config.getMaxRedirects();
            strict302Handling = config.isStrict302Handling();
            compressionEnforced = config.isCompressionEnforced();
            userAgent = config.getUserAgent();
            realm = config.getRealm();
            maxRequestRetry = config.getMaxRequestRetry();
            disableUrlEncodingForBoundRequests = config.isDisableUrlEncodingForBoundRequests();
            disableZeroCopy = config.isDisableZeroCopy();
            keepEncodingHeader = config.isKeepEncodingHeader();
            proxyServerSelector = config.getProxyServerSelector();

            // timeouts
            connectTimeout = config.getConnectTimeout();
            requestTimeout = config.getRequestTimeout();
            readTimeout = config.getReadTimeout();
            shutdownQuietPeriod = config.getShutdownQuietPeriod();
            shutdownTimeout = config.getShutdownTimeout();

            // keep-alive
            keepAlive = config.isKeepAlive();
            pooledConnectionIdleTimeout = config.getPooledConnectionIdleTimeout();
            connectionTtl = config.getConnectionTtl();
            maxConnections = config.getMaxConnections();
            maxConnectionsPerHost = config.getMaxConnectionsPerHost();
            channelPool = config.getChannelPool();
            keepAliveStrategy = config.getKeepAliveStrategy();

            // ssl
            acceptAnyCertificate = config.isAcceptAnyCertificate();
            handshakeTimeout = config.getHandshakeTimeout();
            enabledProtocols = config.getEnabledProtocols();
            enabledCipherSuites = config.getEnabledCipherSuites();
            sslSessionCacheSize = config.getSslSessionCacheSize();
            sslSessionTimeout = config.getSslSessionTimeout();
            sslContext = config.getSslContext();
            sslEngineFactory = config.getSslEngineFactory();

            // filters
            requestFilters.addAll(config.getRequestFilters());
            responseFilters.addAll(config.getResponseFilters());
            ioExceptionFilters.addAll(config.getIoExceptionFilters());

            // tuning
            tcpNoDelay = config.isTcpNoDelay();
            soReuseAddress = config.isSoReuseAddress();
            soLinger = config.getSoLinger();
            soSndBuf = config.getSoSndBuf();
            soRcvBuf = config.getSoRcvBuf();

            // internals
            threadPoolName = config.getThreadPoolName();
            httpClientCodecMaxInitialLineLength = config.getHttpClientCodecMaxInitialLineLength();
            httpClientCodecMaxHeaderSize = config.getHttpClientCodecMaxHeaderSize();
            httpClientCodecMaxChunkSize = config.getHttpClientCodecMaxChunkSize();
            chunkedFileChunkSize = config.getChunkedFileChunkSize();
            webSocketMaxBufferSize = config.getWebSocketMaxBufferSize();
            webSocketMaxFrameSize = config.getWebSocketMaxFrameSize();
            channelOptions.putAll(config.getChannelOptions());
            eventLoopGroup = config.getEventLoopGroup();
            useNativeTransport = config.isUseNativeTransport();
            allocator = config.getAllocator();
            nettyTimer = config.getNettyTimer();
            threadFactory = config.getThreadFactory();
            httpAdditionalChannelInitializer = config.getHttpAdditionalChannelInitializer();
            wsAdditionalChannelInitializer = config.getWsAdditionalChannelInitializer();
            responseBodyPartFactory = config.getResponseBodyPartFactory();
            ioThreadsCount = config.getIoThreadsCount();
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

        public Builder setCompressionEnforced(boolean compressionEnforced) {
            this.compressionEnforced = compressionEnforced;
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
            this.realm = realmBuilder.build();
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
            this.proxyServerSelector = uri -> proxyServer;
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

        // timeouts
        public Builder setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder setRequestTimeout(int requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder setReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public Builder setShutdownQuietPeriod(int shutdownQuietPeriod) {
            this.shutdownQuietPeriod = shutdownQuietPeriod;
            return this;
        }

        public Builder setShutdownTimeout(int shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        // keep-alive
        public Builder setKeepAlive(boolean keepAlive) {
            this.keepAlive = keepAlive;
            return this;
        }

        public Builder setPooledConnectionIdleTimeout(int pooledConnectionIdleTimeout) {
            this.pooledConnectionIdleTimeout = pooledConnectionIdleTimeout;
            return this;
        }

        public Builder setConnectionTtl(int connectionTtl) {
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

        public Builder setChannelPool(ChannelPool channelPool) {
            this.channelPool = channelPool;
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

        public Builder setAcceptAnyCertificate(boolean acceptAnyCertificate) {
            this.acceptAnyCertificate = acceptAnyCertificate;
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

        // tuning
        public Builder setTcpNoDelay(boolean tcpNoDelay) {
            this.tcpNoDelay = tcpNoDelay;
            return this;
        }

        public Builder setSoReuseAddress(boolean soReuseAddress) {
            this.soReuseAddress = soReuseAddress;
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

        public Builder setChunkedFileChunkSize(int chunkedFileChunkSize) {
            this.chunkedFileChunkSize = chunkedFileChunkSize;
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

        public Builder setHttpAdditionalChannelInitializer(AdditionalChannelInitializer httpAdditionalChannelInitializer) {
            this.httpAdditionalChannelInitializer = httpAdditionalChannelInitializer;
            return this;
        }

        public Builder setWsAdditionalChannelInitializer(AdditionalChannelInitializer wsAdditionalChannelInitializer) {
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
            if (proxyServerSelector != null)
                return proxyServerSelector;

            if (useProxySelector)
                return ProxyUtils.getJdkDefaultProxyServerSelector();

            if (useProxyProperties)
                return ProxyUtils.createProxyServerSelector(System.getProperties());

            return ProxyServerSelector.NO_PROXY_SELECTOR;
        }

        public DefaultAsyncHttpClientConfig build() {

            return new DefaultAsyncHttpClientConfig(//
                    followRedirect, //
                    maxRedirects, //
                    strict302Handling, //
                    compressionEnforced, //
                    userAgent, //
                    realm, //
                    maxRequestRetry, //
                    disableUrlEncodingForBoundRequests, //
                    disableZeroCopy, //
                    keepEncodingHeader, //
                    resolveProxyServerSelector(), //
                    validateResponseHeaders, //
                    connectTimeout, //
                    requestTimeout, //
                    readTimeout, //
                    shutdownQuietPeriod, //
                    shutdownTimeout, //
                    keepAlive, //
                    pooledConnectionIdleTimeout, //
                    connectionPoolCleanerPeriod, //
                    connectionTtl, //
                    maxConnections, //
                    maxConnectionsPerHost, //
                    channelPool, //
                    keepAliveStrategy, //
                    useOpenSsl, //
                    acceptAnyCertificate, //
                    handshakeTimeout, //
                    enabledProtocols, //
                    enabledCipherSuites, //
                    sslSessionCacheSize, //
                    sslSessionTimeout, //
                    sslContext, //
                    sslEngineFactory, //
                    requestFilters.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(requestFilters), //
                    responseFilters.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(responseFilters),//
                    ioExceptionFilters.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(ioExceptionFilters),//
                    tcpNoDelay, //
                    soReuseAddress, //
                    soLinger, //
                    soSndBuf, //
                    soRcvBuf, //
                    threadPoolName, //
                    httpClientCodecMaxInitialLineLength, //
                    httpClientCodecMaxHeaderSize, //
                    httpClientCodecMaxChunkSize, //
                    chunkedFileChunkSize, //
                    webSocketMaxBufferSize, //
                    webSocketMaxFrameSize, //
                    channelOptions.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(channelOptions),//
                    eventLoopGroup, //
                    useNativeTransport, //
                    allocator, //
                    nettyTimer, //
                    threadFactory, //
                    httpAdditionalChannelInitializer, //
                    wsAdditionalChannelInitializer, //
                    responseBodyPartFactory, //
                    ioThreadsCount);
        }
    }
}
