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
package org.asynchttpclient;

import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.SSLContext;

import org.asynchttpclient.channel.SSLEngineFactory;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyServerSelector;
import org.asynchttpclient.util.ProxyUtils;

/**
 * Configuration class to use with a {@link AsyncHttpClient}. System property
 * can be also used to configure this object default behavior by doing: <br>
 * -Dorg.asynchttpclient.nameOfTheProperty
 * 
 * @see AsyncHttpClientConfig for documentation
 */
public class DefaultAsyncHttpClientConfig implements AsyncHttpClientConfig {

    private static final String AHC_VERSION;

    static {
        InputStream is = null;
        Properties prop = new Properties();
        try {
            is = DefaultAsyncHttpClientConfig.class.getResourceAsStream("/ahc-version.properties");
            prop.load(is);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
        AHC_VERSION = prop.getProperty("ahc.version", "UNKNOWN");
    }

    private final int connectTimeout;

    private final int maxConnections;
    private final int maxConnectionsPerHost;

    private final int requestTimeout;
    private final int readTimeout;
    private final int webSocketTimeout;

    private final boolean allowPoolingConnections;
    private final int pooledConnectionIdleTimeout;
    private final int connectionTtl;

    private final SSLContext sslContext;
    private final boolean acceptAnyCertificate;

    private final boolean followRedirect;
    private final int maxRedirects;
    private final boolean strict302Handling;

    private final ProxyServerSelector proxyServerSelector;

    private final boolean compressionEnforced;
    private final String userAgent;
    private final String threadPoolName;
    private final ThreadFactory threadFactory;
    private final Realm realm;
    private final List<RequestFilter> requestFilters;
    private final List<ResponseFilter> responseFilters;
    private final List<IOExceptionFilter> ioExceptionFilters;
    private final int maxRequestRetry;
    private final boolean disableUrlEncodingForBoundRequests;
    private final String[] enabledProtocols;
    private final String[] enabledCipherSuites;
    private final Integer sslSessionCacheSize;
    private final Integer sslSessionTimeout;
    private final int httpClientCodecMaxInitialLineLength;
    private final int httpClientCodecMaxHeaderSize;
    private final int httpClientCodecMaxChunkSize;
    private final boolean disableZeroCopy;
    private final long handshakeTimeout;
    private final SSLEngineFactory sslEngineFactory;
    private final int chunkedFileChunkSize;
    private final int webSocketMaxBufferSize;
    private final int webSocketMaxFrameSize;
    private final boolean keepEncodingHeader;
    private final int shutdownQuietPeriod;
    private final int shutdownTimeout;
    private final AdvancedConfig advancedConfig;

    private DefaultAsyncHttpClientConfig(int connectTimeout,//
            int maxConnections,//
            int maxConnectionsPerHost,//
            int requestTimeout,//
            int readTimeout,//
            int webSocketTimeout,//
            boolean allowPoolingConnection,//
            int idleConnectionInPoolTimeout,//
            int connectionTtl,//
            SSLContext sslContext, //
            boolean acceptAnyCertificate, //
            boolean followRedirect, //
            int maxRedirects, //
            boolean strict302Handling, //
            String threadPoolName,//
            ThreadFactory threadFactory,//
            ProxyServerSelector proxyServerSelector, //
            boolean compressionEnforced, //
            String userAgent,//
            Realm realm,//
            List<RequestFilter> requestFilters,//
            List<ResponseFilter> responseFilters,//
            List<IOExceptionFilter> ioExceptionFilters,//
            int maxRequestRetry, //
            boolean disableUrlEncodingForBoundRequests, //
            String[] enabledProtocols,//
            String[] enabledCipherSuites,//
            Integer sslSessionCacheSize,//
            Integer sslSessionTimeout,//
            int httpClientCodecMaxInitialLineLength,//
            int httpClientCodecMaxHeaderSize,//
            int httpClientCodecMaxChunkSize,//
            boolean disableZeroCopy,//
            long handshakeTimeout,//
            SSLEngineFactory sslEngineFactory,//
            int chunkedFileChunkSize,//
            int webSocketMaxBufferSize,//
            int webSocketMaxFrameSize,//
            boolean keepEncodingHeader,//
            int shutdownQuietPeriod,//
            int shutdownTimeout,//
            AdvancedConfig advancedConfig) {

        this.connectTimeout = connectTimeout;
        this.maxConnections = maxConnections;
        this.maxConnectionsPerHost = maxConnectionsPerHost;
        this.requestTimeout = requestTimeout;
        this.readTimeout = readTimeout;
        this.webSocketTimeout = webSocketTimeout;
        this.allowPoolingConnections = allowPoolingConnection;
        this.pooledConnectionIdleTimeout = idleConnectionInPoolTimeout;
        this.connectionTtl = connectionTtl;
        this.sslContext = sslContext;
        this.acceptAnyCertificate = acceptAnyCertificate;
        this.followRedirect = followRedirect;
        this.maxRedirects = maxRedirects;
        this.strict302Handling = strict302Handling;
        this.proxyServerSelector = proxyServerSelector;
        this.compressionEnforced = compressionEnforced;
        this.userAgent = userAgent;
        this.threadPoolName = threadPoolName;
        this.threadFactory = threadFactory;

        this.realm = realm;
        this.requestFilters = requestFilters;
        this.responseFilters = responseFilters;
        this.ioExceptionFilters = ioExceptionFilters;
        this.maxRequestRetry = maxRequestRetry;
        this.disableUrlEncodingForBoundRequests = disableUrlEncodingForBoundRequests;
        this.enabledProtocols = enabledProtocols;
        this.enabledCipherSuites = enabledCipherSuites;
        this.sslSessionCacheSize = sslSessionCacheSize;
        this.sslSessionTimeout = sslSessionTimeout;
        this.advancedConfig = advancedConfig;
        this.httpClientCodecMaxInitialLineLength = httpClientCodecMaxInitialLineLength;
        this.httpClientCodecMaxHeaderSize = httpClientCodecMaxHeaderSize;
        this.httpClientCodecMaxChunkSize = httpClientCodecMaxChunkSize;
        this.disableZeroCopy = disableZeroCopy;
        this.handshakeTimeout = handshakeTimeout;
        this.sslEngineFactory = sslEngineFactory;
        this.chunkedFileChunkSize = chunkedFileChunkSize;
        this.webSocketMaxBufferSize = webSocketMaxBufferSize;
        this.webSocketMaxFrameSize = webSocketMaxFrameSize;
        this.keepEncodingHeader = keepEncodingHeader;
        this.shutdownQuietPeriod = shutdownQuietPeriod;
        this.shutdownTimeout = shutdownTimeout;
    }

    @Override
    public String getAhcVersion() {
        return AHC_VERSION;
    }

    @Override
    public String getThreadPoolName() {
        return threadPoolName;
    }

    @Override
    public String getThreadPoolNameOrDefault() {
        String r = threadPoolName;
        if (r == null || r.isEmpty()) {
            r = defaultThreadPoolName();
        }
        if (r == null || r.isEmpty()) {
            r = "AsyncHttpClient";
        }
        return r;
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
    public int getConnectTimeout() {
        return connectTimeout;
    }

    @Override
    public int getWebSocketTimeout() {
        return webSocketTimeout;
    }

    @Override
    public int getReadTimeout() {
        return readTimeout;
    }

    @Override
    public int getPooledConnectionIdleTimeout() {
        return pooledConnectionIdleTimeout;
    }

    @Override
    public int getRequestTimeout() {
        return requestTimeout;
    }

    @Override
    public boolean isFollowRedirect() {
        return followRedirect;
    }

    @Override
    public int getMaxRedirects() {
        return maxRedirects;
    }

    @Override
    public boolean isAllowPoolingConnections() {
        return allowPoolingConnections;
    }

    @Override
    public String getUserAgent() {
        return userAgent;
    }

    @Override
    public boolean isCompressionEnforced() {
        return compressionEnforced;
    }

    @Override
    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    @Override
    public ProxyServerSelector getProxyServerSelector() {
        return proxyServerSelector;
    }

    @Override
    public SSLContext getSslContext() {
        return sslContext;
    }

    @Override
    public AdvancedConfig getAdvancedConfig() {
        return advancedConfig;
    }

    @Override
    public Realm getRealm() {
        return realm;
    }

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

    @Override
    public int getMaxRequestRetry() {
        return maxRequestRetry;
    }

    @Override
    public boolean isDisableUrlEncodingForBoundRequests() {
        return disableUrlEncodingForBoundRequests;
    }

    @Override
    public boolean isStrict302Handling() {
        return strict302Handling;
    }

    @Override
    public int getConnectionTtl() {
        return connectionTtl;
    }

    @Override
    public boolean isAcceptAnyCertificate() {
        return acceptAnyCertificate;
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
    public Integer getSslSessionCacheSize() {
        return sslSessionCacheSize;
    }

    @Override
    public Integer getSslSessionTimeout() {
        return sslSessionTimeout;
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
    public boolean isDisableZeroCopy() {
        return disableZeroCopy;
    }

    @Override
    public long getHandshakeTimeout() {
        return handshakeTimeout;
    }

    @Override
    public SSLEngineFactory getSslEngineFactory() {
        return sslEngineFactory;
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
    public boolean isKeepEncodingHeader() {
        return keepEncodingHeader;
    }

    @Override
    public int getShutdownQuietPeriod() {
        return shutdownQuietPeriod;
    }

    @Override
    public int getShutdownTimeout() {
        return shutdownTimeout;
    }

    /**
     * Builder for an {@link AsyncHttpClient}
     */
    public static class Builder {
        private int connectTimeout = defaultConnectTimeout();
        private int maxConnections = defaultMaxConnections();
        private int maxConnectionsPerHost = defaultMaxConnectionsPerHost();
        private int requestTimeout = defaultRequestTimeout();
        private int readTimeout = defaultReadTimeout();
        private int webSocketTimeout = defaultWebSocketTimeout();
        private boolean allowPoolingConnections = defaultAllowPoolingConnections();
        private int pooledConnectionIdleTimeout = defaultPooledConnectionIdleTimeout();
        private int connectionTtl = defaultConnectionTtl();
        private SSLContext sslContext;
        private boolean acceptAnyCertificate = defaultAcceptAnyCertificate();
        private boolean followRedirect = defaultFollowRedirect();
        private int maxRedirects = defaultMaxRedirects();
        private boolean strict302Handling = defaultStrict302Handling();
        private ProxyServerSelector proxyServerSelector;
        private boolean useProxySelector = defaultUseProxySelector();
        private boolean useProxyProperties = defaultUseProxyProperties();
        private boolean compressionEnforced = defaultCompressionEnforced();
        private String userAgent = defaultUserAgent();
        private String threadPoolName = defaultThreadPoolName();
        private ThreadFactory threadFactory;
        private Realm realm;
        private final List<RequestFilter> requestFilters = new LinkedList<>();
        private final List<ResponseFilter> responseFilters = new LinkedList<>();
        private final List<IOExceptionFilter> ioExceptionFilters = new LinkedList<>();
        private int maxRequestRetry = defaultMaxRequestRetry();
        private boolean disableUrlEncodingForBoundRequests = defaultDisableUrlEncodingForBoundRequests();
        private String[] enabledProtocols = defaultEnabledProtocols();
        private String[] enabledCipherSuites;
        private Integer sslSessionCacheSize = defaultSslSessionCacheSize();
        private Integer sslSessionTimeout = defaultSslSessionTimeout();
        private int httpClientCodecMaxInitialLineLength = defaultHttpClientCodecMaxInitialLineLength();
        private int httpClientCodecMaxHeaderSize = defaultHttpClientCodecMaxHeaderSize();
        private int httpClientCodecMaxChunkSize = defaultHttpClientCodecMaxChunkSize();
        private boolean disableZeroCopy = defaultDisableZeroCopy();
        private long handshakeTimeout = defaultHandshakeTimeout();
        private SSLEngineFactory sslEngineFactory;
        private int chunkedFileChunkSize = defaultChunkedFileChunkSize();
        private int webSocketMaxBufferSize = defaultWebSocketMaxBufferSize();
        private int webSocketMaxFrameSize = defaultWebSocketMaxFrameSize();
        private boolean keepEncodingHeader = defaultKeepEncodingHeader();
        private int shutdownQuietPeriod = defaultShutdownQuietPeriod();
        private int shutdownTimeout = defaultShutdownTimeout();
        private AdvancedConfig advancedConfig;

        public Builder() {
        }

        public Builder(AsyncHttpClientConfig config) {
            connectTimeout = config.getConnectTimeout();
            maxConnections = config.getMaxConnections();
            maxConnectionsPerHost = config.getMaxConnectionsPerHost();
            requestTimeout = config.getRequestTimeout();
            readTimeout = config.getReadTimeout();
            webSocketTimeout = config.getWebSocketTimeout();
            allowPoolingConnections = config.isAllowPoolingConnections();
            pooledConnectionIdleTimeout = config.getPooledConnectionIdleTimeout();
            connectionTtl = config.getConnectionTtl();
            sslContext = config.getSslContext();
            acceptAnyCertificate = config.isAcceptAnyCertificate();
            followRedirect = config.isFollowRedirect();
            maxRedirects = config.getMaxRedirects();
            strict302Handling = config.isStrict302Handling();
            proxyServerSelector = config.getProxyServerSelector();
            compressionEnforced = config.isCompressionEnforced();
            userAgent = config.getUserAgent();
            threadPoolName = config.getThreadPoolName();
            threadFactory = config.getThreadFactory();
            realm = config.getRealm();
            requestFilters.addAll(config.getRequestFilters());
            responseFilters.addAll(config.getResponseFilters());
            ioExceptionFilters.addAll(config.getIoExceptionFilters());
            maxRequestRetry = config.getMaxRequestRetry();
            disableUrlEncodingForBoundRequests = config.isDisableUrlEncodingForBoundRequests();
            enabledProtocols = config.getEnabledProtocols();
            enabledCipherSuites = config.getEnabledCipherSuites();
            sslSessionCacheSize = config.getSslSessionCacheSize();
            sslSessionTimeout = config.getSslSessionTimeout();
            httpClientCodecMaxInitialLineLength = config.getHttpClientCodecMaxInitialLineLength();
            httpClientCodecMaxHeaderSize = config.getHttpClientCodecMaxHeaderSize();
            httpClientCodecMaxChunkSize = config.getHttpClientCodecMaxChunkSize();
            disableZeroCopy = config.isDisableZeroCopy();
            handshakeTimeout = config.getHandshakeTimeout();
            sslEngineFactory = config.getSslEngineFactory();
            chunkedFileChunkSize = config.getChunkedFileChunkSize();
            webSocketMaxBufferSize = config.getWebSocketMaxBufferSize();
            webSocketMaxFrameSize = config.getWebSocketMaxFrameSize();
            keepEncodingHeader = config.isKeepEncodingHeader();
            shutdownQuietPeriod = config.getShutdownQuietPeriod();
            shutdownTimeout = config.getShutdownTimeout();
            advancedConfig = config.getAdvancedConfig();
        }

        public Builder setThreadPoolName(String threadPoolName) {
            this.threadPoolName = threadPoolName;
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

        public Builder setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder setWebSocketTimeout(int webSocketTimeout) {
            this.webSocketTimeout = webSocketTimeout;
            return this;
        }

        public Builder setReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public Builder setPooledConnectionIdleTimeout(int pooledConnectionIdleTimeout) {
            this.pooledConnectionIdleTimeout = pooledConnectionIdleTimeout;
            return this;
        }

        public Builder setRequestTimeout(int requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder setFollowRedirect(boolean followRedirect) {
            this.followRedirect = followRedirect;
            return this;
        }

        public Builder setMaxRedirects(int maxRedirects) {
            this.maxRedirects = maxRedirects;
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

        public Builder setAllowPoolingConnections(boolean allowPoolingConnections) {
            this.allowPoolingConnections = allowPoolingConnections;
            return this;
        }

        public Builder setThreadFactory(ThreadFactory threadFactory) {
            this.threadFactory = threadFactory;
            return this;
        }

        public Builder setProxyServerSelector(ProxyServerSelector proxyServerSelector) {
            this.proxyServerSelector = proxyServerSelector;
            return this;
        }

        public Builder setProxyServer(ProxyServer proxyServer) {
            this.proxyServerSelector = ProxyUtils.createProxyServerSelector(proxyServer);
            return this;
        }

        public Builder setSslContext(final SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        public Builder setAdvancedConfig(AdvancedConfig advancedConfig) {
            this.advancedConfig = advancedConfig;
            return this;
        }

        public Builder setRealm(Realm realm) {
            this.realm = realm;
            return this;
        }

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

        public Builder setMaxRequestRetry(int maxRequestRetry) {
            this.maxRequestRetry = maxRequestRetry;
            return this;
        }

        public Builder setDisableUrlEncodingForBoundRequests(boolean disableUrlEncodingForBoundRequests) {
            this.disableUrlEncodingForBoundRequests = disableUrlEncodingForBoundRequests;
            return this;
        }

        public Builder setUseProxySelector(boolean useProxySelector) {
            this.useProxySelector = useProxySelector;
            return this;
        }

        public Builder setUseProxyProperties(boolean useProxyProperties) {
            this.useProxyProperties = useProxyProperties;
            return this;
        }

        public Builder setStrict302Handling(final boolean strict302Handling) {
            this.strict302Handling = strict302Handling;
            return this;
        }

        public Builder setConnectionTtl(int connectionTtl) {
            this.connectionTtl = connectionTtl;
            return this;
        }

        public Builder setAcceptAnyCertificate(boolean acceptAnyCertificate) {
            this.acceptAnyCertificate = acceptAnyCertificate;
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

        public Builder setDisableZeroCopy(boolean disableZeroCopy) {
            this.disableZeroCopy = disableZeroCopy;
            return this;
        }

        public Builder setHandshakeTimeout(long handshakeTimeout) {
            this.handshakeTimeout = handshakeTimeout;
            return this;
        }

        public Builder setSslEngineFactory(SSLEngineFactory sslEngineFactory) {
            this.sslEngineFactory = sslEngineFactory;
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

        public Builder setKeepEncodingHeader(boolean keepEncodingHeader) {
            this.keepEncodingHeader = keepEncodingHeader;
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

        public DefaultAsyncHttpClientConfig build() {

            if (proxyServerSelector == null && useProxySelector)
                proxyServerSelector = ProxyUtils.getJdkDefaultProxyServerSelector();

            if (proxyServerSelector == null && useProxyProperties)
                proxyServerSelector = ProxyUtils.createProxyServerSelector(System.getProperties());

            if (proxyServerSelector == null)
                proxyServerSelector = ProxyServerSelector.NO_PROXY_SELECTOR;

            return new DefaultAsyncHttpClientConfig(connectTimeout,//
                    maxConnections,//
                    maxConnectionsPerHost,//
                    requestTimeout,//
                    readTimeout,//
                    webSocketTimeout,//
                    allowPoolingConnections,//
                    pooledConnectionIdleTimeout,//
                    connectionTtl,//
                    sslContext, //
                    acceptAnyCertificate, //
                    followRedirect, //
                    maxRedirects, //
                    strict302Handling, //
                    threadPoolName,//
                    threadFactory, //
                    proxyServerSelector, //
                    compressionEnforced, //
                    userAgent,//
                    realm,//
                    requestFilters.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(requestFilters), //
                    responseFilters.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(responseFilters),//
                    ioExceptionFilters.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(ioExceptionFilters),//
                    maxRequestRetry, //
                    disableUrlEncodingForBoundRequests, //
                    enabledProtocols, //
                    enabledCipherSuites, //
                    sslSessionCacheSize, //
                    sslSessionTimeout, //
                    httpClientCodecMaxInitialLineLength, //
                    httpClientCodecMaxHeaderSize, //
                    httpClientCodecMaxChunkSize, //
                    disableZeroCopy, //
                    handshakeTimeout, //
                    sslEngineFactory, //
                    chunkedFileChunkSize, //
                    webSocketMaxBufferSize, //
                    webSocketMaxFrameSize, //
                    keepEncodingHeader, //
                    shutdownQuietPeriod,//
                    shutdownTimeout,//
                    advancedConfig);
        }
    }
}
