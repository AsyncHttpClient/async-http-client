/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class AsyncHttpClientConfigDefaults {

  public static final String ASYNC_CLIENT_CONFIG_ROOT = "org.asynchttpclient.";
  public static final String THREAD_POOL_NAME_CONFIG = "threadPoolName";
  public static final String MAX_CONNECTIONS_CONFIG = "maxConnections";
  public static final String MAX_CONNECTIONS_PER_HOST_CONFIG = "maxConnectionsPerHost";
  public static final String ACQUIRE_FREE_CHANNEL_TIMEOUT = "acquireFreeChannelTimeout";
  public static final String CONNECTION_TIMEOUT_CONFIG = "connectTimeout";
  public static final String POOLED_CONNECTION_IDLE_TIMEOUT_CONFIG = "pooledConnectionIdleTimeout";
  public static final String CONNECTION_POOL_CLEANER_PERIOD_CONFIG = "connectionPoolCleanerPeriod";
  public static final String READ_TIMEOUT_CONFIG = "readTimeout";
  public static final String REQUEST_TIMEOUT_CONFIG = "requestTimeout";
  public static final String CONNECTION_TTL_CONFIG = "connectionTtl";
  public static final String FOLLOW_REDIRECT_CONFIG = "followRedirect";
  public static final String MAX_REDIRECTS_CONFIG = "maxRedirects";
  public static final String COMPRESSION_ENFORCED_CONFIG = "compressionEnforced";
  public static final String USER_AGENT_CONFIG = "userAgent";
  public static final String ENABLED_PROTOCOLS_CONFIG = "enabledProtocols";
  public static final String ENABLED_CIPHER_SUITES_CONFIG = "enabledCipherSuites";
  public static final String FILTER_INSECURE_CIPHER_SUITES_CONFIG = "filterInsecureCipherSuites";
  public static final String USE_PROXY_SELECTOR_CONFIG = "useProxySelector";
  public static final String USE_PROXY_PROPERTIES_CONFIG = "useProxyProperties";
  public static final String VALIDATE_RESPONSE_HEADERS_CONFIG = "validateResponseHeaders";
  public static final String AGGREGATE_WEBSOCKET_FRAME_FRAGMENTS_CONFIG = "aggregateWebSocketFrameFragments";
  public static final String ENABLE_WEBSOCKET_COMPRESSION_CONFIG = "enableWebSocketCompression";
  public static final String STRICT_302_HANDLING_CONFIG = "strict302Handling";
  public static final String KEEP_ALIVE_CONFIG = "keepAlive";
  public static final String MAX_REQUEST_RETRY_CONFIG = "maxRequestRetry";
  public static final String DISABLE_URL_ENCODING_FOR_BOUND_REQUESTS_CONFIG = "disableUrlEncodingForBoundRequests";
  public static final String USE_LAX_COOKIE_ENCODER_CONFIG = "useLaxCookieEncoder";
  public static final String USE_OPEN_SSL_CONFIG = "useOpenSsl";
  public static final String USE_INSECURE_TRUST_MANAGER_CONFIG = "useInsecureTrustManager";
  public static final String DISABLE_HTTPS_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG = "disableHttpsEndpointIdentificationAlgorithm";
  public static final String SSL_SESSION_CACHE_SIZE_CONFIG = "sslSessionCacheSize";
  public static final String SSL_SESSION_TIMEOUT_CONFIG = "sslSessionTimeout";
  public static final String TCP_NO_DELAY_CONFIG = "tcpNoDelay";
  public static final String SO_REUSE_ADDRESS_CONFIG = "soReuseAddress";
  public static final String SO_LINGER_CONFIG = "soLinger";
  public static final String SO_SND_BUF_CONFIG = "soSndBuf";
  public static final String SO_RCV_BUF_CONFIG = "soRcvBuf";
  public static final String HTTP_CLIENT_CODEC_MAX_INITIAL_LINE_LENGTH_CONFIG = "httpClientCodecMaxInitialLineLength";
  public static final String HTTP_CLIENT_CODEC_MAX_HEADER_SIZE_CONFIG = "httpClientCodecMaxHeaderSize";
  public static final String HTTP_CLIENT_CODEC_MAX_CHUNK_SIZE_CONFIG = "httpClientCodecMaxChunkSize";
  public static final String HTTP_CLIENT_CODEC_INITIAL_BUFFER_SIZE_CONFIG = "httpClientCodecInitialBufferSize";
  public static final String DISABLE_ZERO_COPY_CONFIG = "disableZeroCopy";
  public static final String HANDSHAKE_TIMEOUT_CONFIG = "handshakeTimeout";
  public static final String CHUNKED_FILE_CHUNK_SIZE_CONFIG = "chunkedFileChunkSize";
  public static final String WEBSOCKET_MAX_BUFFER_SIZE_CONFIG = "webSocketMaxBufferSize";
  public static final String WEBSOCKET_MAX_FRAME_SIZE_CONFIG = "webSocketMaxFrameSize";
  public static final String KEEP_ENCODING_HEADER_CONFIG = "keepEncodingHeader";
  public static final String SHUTDOWN_QUIET_PERIOD_CONFIG = "shutdownQuietPeriod";
  public static final String SHUTDOWN_TIMEOUT_CONFIG = "shutdownTimeout";
  public static final String USE_NATIVE_TRANSPORT_CONFIG = "useNativeTransport";
  public static final String IO_THREADS_COUNT_CONFIG = "ioThreadsCount";

  public static final String AHC_VERSION;

  static {
    try (InputStream is = AsyncHttpClientConfigDefaults.class.getResourceAsStream("ahc-version.properties")) {
      Properties prop = new Properties();
      prop.load(is);
      AHC_VERSION = prop.getProperty("ahc.version", "UNKNOWN");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private AsyncHttpClientConfigDefaults() {
  }

  public static String defaultThreadPoolName() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getString(ASYNC_CLIENT_CONFIG_ROOT + THREAD_POOL_NAME_CONFIG);
  }

  public static int defaultMaxConnections() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + MAX_CONNECTIONS_CONFIG);
  }

  public static int defaultMaxConnectionsPerHost() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + MAX_CONNECTIONS_PER_HOST_CONFIG);
  }

  public static int defaultAcquireFreeChannelTimeout() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + ACQUIRE_FREE_CHANNEL_TIMEOUT);
  }

  public static int defaultConnectTimeout() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + CONNECTION_TIMEOUT_CONFIG);
  }

  public static int defaultPooledConnectionIdleTimeout() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + POOLED_CONNECTION_IDLE_TIMEOUT_CONFIG);
  }

  public static int defaultConnectionPoolCleanerPeriod() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + CONNECTION_POOL_CLEANER_PERIOD_CONFIG);
  }

  public static int defaultReadTimeout() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + READ_TIMEOUT_CONFIG);
  }

  public static int defaultRequestTimeout() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + REQUEST_TIMEOUT_CONFIG);
  }

  public static int defaultConnectionTtl() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + CONNECTION_TTL_CONFIG);
  }

  public static boolean defaultFollowRedirect() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + FOLLOW_REDIRECT_CONFIG);
  }

  public static int defaultMaxRedirects() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + MAX_REDIRECTS_CONFIG);
  }

  public static boolean defaultCompressionEnforced() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + COMPRESSION_ENFORCED_CONFIG);
  }

  public static String defaultUserAgent() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getString(ASYNC_CLIENT_CONFIG_ROOT + USER_AGENT_CONFIG);
  }

  public static String[] defaultEnabledProtocols() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getStringArray(ASYNC_CLIENT_CONFIG_ROOT + ENABLED_PROTOCOLS_CONFIG);
  }

  public static String[] defaultEnabledCipherSuites() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getStringArray(ASYNC_CLIENT_CONFIG_ROOT + ENABLED_CIPHER_SUITES_CONFIG);
  }

  public static boolean defaultFilterInsecureCipherSuites() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + FILTER_INSECURE_CIPHER_SUITES_CONFIG);
  }

  public static boolean defaultUseProxySelector() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + USE_PROXY_SELECTOR_CONFIG);
  }

  public static boolean defaultUseProxyProperties() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + USE_PROXY_PROPERTIES_CONFIG);
  }

  public static boolean defaultValidateResponseHeaders() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + VALIDATE_RESPONSE_HEADERS_CONFIG);
  }

  public static boolean defaultAggregateWebSocketFrameFragments() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + AGGREGATE_WEBSOCKET_FRAME_FRAGMENTS_CONFIG);
  }

  public static boolean defaultEnableWebSocketCompression() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + ENABLE_WEBSOCKET_COMPRESSION_CONFIG);
  }

  public static boolean defaultStrict302Handling() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + STRICT_302_HANDLING_CONFIG);
  }

  public static boolean defaultKeepAlive() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + KEEP_ALIVE_CONFIG);
  }

  public static int defaultMaxRequestRetry() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + MAX_REQUEST_RETRY_CONFIG);
  }

  public static boolean defaultDisableUrlEncodingForBoundRequests() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + DISABLE_URL_ENCODING_FOR_BOUND_REQUESTS_CONFIG);
  }

  public static boolean defaultUseLaxCookieEncoder() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + USE_LAX_COOKIE_ENCODER_CONFIG);
  }

  public static boolean defaultUseOpenSsl() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + USE_OPEN_SSL_CONFIG);
  }

  public static boolean defaultUseInsecureTrustManager() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + USE_INSECURE_TRUST_MANAGER_CONFIG);
  }

  public static boolean defaultDisableHttpsEndpointIdentificationAlgorithm() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + DISABLE_HTTPS_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG);
  }

  public static int defaultSslSessionCacheSize() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + SSL_SESSION_CACHE_SIZE_CONFIG);
  }

  public static int defaultSslSessionTimeout() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + SSL_SESSION_TIMEOUT_CONFIG);
  }

  public static boolean defaultTcpNoDelay() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + TCP_NO_DELAY_CONFIG);
  }

  public static boolean defaultSoReuseAddress() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + SO_REUSE_ADDRESS_CONFIG);
  }

  public static int defaultSoLinger() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + SO_LINGER_CONFIG);
  }

  public static int defaultSoSndBuf() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + SO_SND_BUF_CONFIG);
  }

  public static int defaultSoRcvBuf() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + SO_RCV_BUF_CONFIG);
  }

  public static int defaultHttpClientCodecMaxInitialLineLength() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + HTTP_CLIENT_CODEC_MAX_INITIAL_LINE_LENGTH_CONFIG);
  }

  public static int defaultHttpClientCodecMaxHeaderSize() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + HTTP_CLIENT_CODEC_MAX_HEADER_SIZE_CONFIG);
  }

  public static int defaultHttpClientCodecMaxChunkSize() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + HTTP_CLIENT_CODEC_MAX_CHUNK_SIZE_CONFIG);
  }

  public static int defaultHttpClientCodecInitialBufferSize() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + HTTP_CLIENT_CODEC_INITIAL_BUFFER_SIZE_CONFIG);
  }

  public static boolean defaultDisableZeroCopy() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + DISABLE_ZERO_COPY_CONFIG);
  }

  public static int defaultHandshakeTimeout() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + HANDSHAKE_TIMEOUT_CONFIG);
  }

  public static int defaultChunkedFileChunkSize() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + CHUNKED_FILE_CHUNK_SIZE_CONFIG);
  }

  public static int defaultWebSocketMaxBufferSize() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + WEBSOCKET_MAX_BUFFER_SIZE_CONFIG);
  }

  public static int defaultWebSocketMaxFrameSize() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + WEBSOCKET_MAX_FRAME_SIZE_CONFIG);
  }

  public static boolean defaultKeepEncodingHeader() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + KEEP_ENCODING_HEADER_CONFIG);
  }

  public static int defaultShutdownQuietPeriod() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + SHUTDOWN_QUIET_PERIOD_CONFIG);
  }

  public static int defaultShutdownTimeout() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + SHUTDOWN_TIMEOUT_CONFIG);
  }

  public static boolean defaultUseNativeTransport() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + USE_NATIVE_TRANSPORT_CONFIG);
  }

  public static int defaultIoThreadsCount() {
    return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + IO_THREADS_COUNT_CONFIG);
  }
}
