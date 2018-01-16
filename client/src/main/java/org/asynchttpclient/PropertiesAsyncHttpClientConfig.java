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
import org.asynchttpclient.config.AsyncHttpClientConfigHelper;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.cookie.ThreadSafeCookieStore;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.proxy.ProxyServerSelector;

import java.util.*;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.*;

public class PropertiesAsyncHttpClientConfig implements AsyncHttpClientConfig {

    private final AsyncHttpClientConfigHelper.Config config;

    public PropertiesAsyncHttpClientConfig(Properties properties) {
        this.config = new AsyncHttpClientConfigHelper.Config(properties);
    }

    @Override
    public String getAhcVersion() {
        return AsyncHttpClientConfigDefaults.AHC_VERSION;
    }

    @Override
    public String getThreadPoolName() {
        return config.getStringOpt(THREAD_POOL_NAME_CONFIG).orElse(defaultThreadPoolName());
    }

    @Override
    public int getMaxConnections() {
        return config.getIntOpt(MAX_CONNECTIONS_CONFIG).orElse(defaultMaxConnections());
    }

    @Override
    public int getMaxConnectionsPerHost() {
        return config.getIntOpt(MAX_CONNECTIONS_PER_HOST_CONFIG).orElse(defaultMaxConnectionsPerHost());
    }

    @Override
    public int getConnectTimeout() {
        return config.getIntOpt(CONNECTION_TIMEOUT_CONFIG).orElse(defaultConnectTimeout());
    }

    @Override
    public int getReadTimeout() {
        return config.getIntOpt(READ_TIMEOUT_CONFIG).orElse(defaultReadTimeout());
    }

    @Override
    public int getPooledConnectionIdleTimeout() {
        return config.getIntOpt(POOLED_CONNECTION_IDLE_TIMEOUT_CONFIG).orElse(defaultPooledConnectionIdleTimeout());
    }

    @Override
    public int getConnectionPoolCleanerPeriod() {
        return config.getIntOpt(CONNECTION_POOL_CLEANER_PERIOD_CONFIG).orElse(defaultConnectionPoolCleanerPeriod());
    }

    @Override
    public int getRequestTimeout() {
        return config.getIntOpt(READ_TIMEOUT_CONFIG).orElse(defaultReadTimeout());
    }

    @Override
    public boolean isFollowRedirect() {
        return config.getBooleanOpt(FOLLOW_REDIRECT_CONFIG).orElse(defaultFollowRedirect());
    }

    @Override
    public int getMaxRedirects() {
        return config.getIntOpt(MAX_REDIRECT_CONFIG).orElse(defaultMaxRedirects());
    }

    @Override
    public boolean isKeepAlive() {
        return config.getBooleanOpt(KEEP_ALIVE_CONFIG).orElse(defaultKeepAlive());
    }

    @Override
    public String getUserAgent() {
        return config.getStringOpt(USER_AGENT_CONFIG).orElse(defaultUserAgent());
    }

    @Override
    public boolean isCompressionEnforced() {
        return config.getBooleanOpt(COMPRESSION_ENFORCED_CONFIG).orElse(defaultCompressionEnforced());
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
        return config.getIntOpt(MAX_REQUEST_RETRY_CONFIG).orElse(defaultMaxRequestRetry());
    }

    @Override
    public boolean isDisableUrlEncodingForBoundRequests() {
        return config.getBooleanOpt(DISABLE_URL_ENCODING_FOR_BOUND_REQUESTS_CONFIG).orElse(defaultDisableUrlEncodingForBoundRequests());
    }

    @Override
    public boolean isUseLaxCookieEncoder() {
        return config.getBooleanOpt(USE_LAX_COOKIE_ENCODER_CONFIG).orElse(defaultUseLaxCookieEncoder());
    }

    @Override
    public boolean isStrict302Handling() {
        return config.getBooleanOpt(STRICT_302_HANDLING_CONFIG).orElse(defaultStrict302Handling());
    }

    @Override
    public int getConnectionTtl() {
        return config.getIntOpt(CONNECTION_TTL_CONFIG).orElse(defaultConnectionTtl());
    }

    @Override
    public boolean isUseOpenSsl() {
        return config.getBooleanOpt(USE_OPEN_SSL_CONFIG).orElse(defaultUseOpenSsl());
    }

    @Override
    public boolean isUseInsecureTrustManager() {
        return config.getBooleanOpt(USE_INSECURE_TRUST_MANAGER_CONFIG).orElse(defaultUseInsecureTrustManager());
    }

    @Override
    public boolean isDisableHttpsEndpointIdentificationAlgorithm() {
        return config.getBooleanOpt(DISABLE_HTTPS_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG).orElse(defaultDisableHttpsEndpointIdentificationAlgorithm());
    }

    @Override
    public String[] getEnabledProtocols() {
        return config.getStringArrayOpt(ENABLED_PROTOCOLS_CONFIG).orElse(defaultEnabledProtocols());
    }

    @Override
    public String[] getEnabledCipherSuites() {
        return config.getStringArrayOpt(ENABLED_CIPHER_SUITES_CONFIG).orElse(defaultEnabledCipherSuites());
    }

    @Override
    public int getSslSessionCacheSize() {
        return config.getIntOpt(SSL_SESSION_CACHE_SIZE_CONFIG).orElse(defaultSslSessionCacheSize());
    }

    @Override
    public int getSslSessionTimeout() {
        return config.getIntOpt(SSL_SESSION_TIMEOUT_CONFIG).orElse(defaultSslSessionTimeout());
    }

    @Override
    public int getHttpClientCodecMaxInitialLineLength() {
        return config.getIntOpt(HTTP_CLIENT_CODEC_MAX_INITIAL_LINE_LENGTH_CONFIG).orElse(defaultHttpClientCodecMaxInitialLineLength());
    }

    @Override
    public int getHttpClientCodecMaxHeaderSize() {
        return config.getIntOpt(HTTP_CLIENT_CODEC_MAX_HEADER_SIZE_CONFIG).orElse(defaultHttpClientCodecMaxHeaderSize());
    }

    @Override
    public int getHttpClientCodecMaxChunkSize() {
        return config.getIntOpt(HTTP_CLIENT_CODEC_MAX_CHUNK_SIZE_CONFIG).orElse(defaultHttpClientCodecMaxChunkSize());
    }

    @Override
    public int getHttpClientCodecInitialBufferSize() {
        return config.getIntOpt(HTTP_CLIENT_CODEC_INITIAL_BUFFER_SIZE_CONFIG).orElse(defaultHttpClientCodecInitialBufferSize());
    }

    @Override
    public boolean isDisableZeroCopy() {
        return config.getBooleanOpt(DISABLE_ZERO_COPY_CONFIG).orElse(defaultDisableZeroCopy());
    }

    @Override
    public int getHandshakeTimeout() {
        return config.getIntOpt(HANDSHAKE_TIMEOUT_CONFIG).orElse(defaultHandshakeTimeout());
    }

    @Override
    public SslEngineFactory getSslEngineFactory() {
        return null;
    }

    @Override
    public int getChunkedFileChunkSize() {
        return config.getIntOpt(CHUNKED_FILE_CHUNK_SIZE_CONFIG).orElse(defaultChunkedFileChunkSize());
    }

    @Override
    public int getWebSocketMaxBufferSize() {
        return config.getIntOpt(WEB_SOCKET_MAX_BUFFER_SIZE_CONFIG).orElse(defaultWebSocketMaxBufferSize());
    }

    @Override
    public int getWebSocketMaxFrameSize() {
        return config.getIntOpt(WEB_SOCKET_MAX_FRAME_SIZE_CONFIG).orElse(defaultWebSocketMaxFrameSize());
    }

    @Override
    public boolean isKeepEncodingHeader() {
        return config.getBooleanOpt(KEEP_ENCODING_HEADER_CONFIG).orElse(defaultKeepEncodingHeader());
    }

    @Override
    public int getShutdownQuietPeriod() {
        return config.getIntOpt(SHUTDOWN_QUIET_PERIOD_CONFIG).orElse(defaultShutdownQuietPeriod());
    }

    @Override
    public int getShutdownTimeout() {
        return config.getIntOpt(SHUTDOWN_TIMEOUT_CONFIG).orElse(defaultShutdownTimeout());
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
        return config.getBooleanOpt(USE_NATIVE_TRANSPORT_CONFIG).orElse(defaultUseNativeTransport());
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
    public Timer getNettyTimer() {
        return null;
    }

    @Override
    public KeepAliveStrategy getKeepAliveStrategy() {
        return new DefaultKeepAliveStrategy();
    }

    @Override
    public boolean isValidateResponseHeaders() {
        return config.getBooleanOpt(VALIDATE_RESPONSE_HEADERS_CONFIG).orElse(defaultValidateResponseHeaders());
    }

    @Override
    public boolean isAggregateWebSocketFrameFragments() {
        return config.getBooleanOpt(AGGREGATE_WEB_SOCKET_FRAME_FRAGMENTS_CONFIG).orElse(defaultAggregateWebSocketFrameFragments());
    }

    @Override
    public boolean isTcpNoDelay() {
        return config.getBooleanOpt(TCP_NO_DELAY_CONFIG).orElse(defaultTcpNoDelay());
    }

    @Override
    public boolean isSoReuseAddress() {
        return config.getBooleanOpt(SO_REUSE_ADDRESS_CONFIG).orElse(defaultSoReuseAddress());
    }

    @Override
    public int getSoLinger() {
        return config.getIntOpt(SO_LINGER_CONFIG).orElse(defaultSoLinger());
    }

    @Override
    public int getSoSndBuf() {
        return config.getIntOpt(SO_SND_BUF_CONFIG).orElse(defaultSoSndBuf());
    }

    @Override
    public int getSoRcvBuf() {
        return config.getIntOpt(SO_RCV_BUF_CONFIG).orElse(defaultSoRcvBuf());
    }

    @Override
    public ByteBufAllocator getAllocator() {
        return null;
    }

    @Override
    public int getIoThreadsCount() {
        return config.getIntOpt(IO_THREADS_COUNT_CONFIG).orElse(defaultIoThreadsCount());
    }

}
