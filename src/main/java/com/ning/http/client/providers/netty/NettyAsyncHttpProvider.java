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
package com.ning.http.client.providers.netty;

import static com.ning.http.util.AsyncHttpProviderUtils.DEFAULT_CHARSET;
import static com.ning.http.util.AsyncHttpProviderUtils.getNonEmptyPath;
import static com.ning.http.util.MiscUtils.isNonEmpty;
import static org.jboss.netty.channel.Channels.pipeline;
import static org.jboss.netty.handler.ssl.SslHandler.getDefaultBufferPool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLEngine;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureProgressListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.DefaultChannelFuture;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.FileRegion;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.PrematureChannelClosureException;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpClientCodec;
import org.jboss.netty.handler.codec.http.HttpContentDecompressor;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket08FrameDecoder;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket08FrameEncoder;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedFile;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.Timer;
import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHandler.STATE;
import com.ning.http.client.AsyncHandlerExtensions;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;
import com.ning.http.client.ConnectionPoolKeyStrategy;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.ProgressAsyncHandler;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.RandomAccessBody;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.cookie.CookieDecoder;
import com.ning.http.client.cookie.CookieEncoder;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.FilterException;
import com.ning.http.client.filter.IOExceptionFilter;
import com.ning.http.client.filter.ResponseFilter;
import com.ning.http.client.generators.InputStreamBodyGenerator;
import com.ning.http.client.listener.TransferCompletionHandler;
import com.ning.http.client.ntlm.NTLMEngine;
import com.ning.http.client.ntlm.NTLMEngineException;
import com.ning.http.client.providers.netty.pool.ChannelManager;
import com.ning.http.client.providers.netty.pool.ChannelPool;
import com.ning.http.client.providers.netty.pool.DefaultChannelPool;
import com.ning.http.client.providers.netty.pool.NoopChannelPool;
import com.ning.http.client.providers.netty.spnego.SpnegoEngine;
import com.ning.http.client.providers.netty.timeout.IdleConnectionTimeoutTimerTask;
import com.ning.http.client.providers.netty.timeout.RequestTimeoutTimerTask;
import com.ning.http.client.providers.netty.timeout.TimeoutsHolder;
import com.ning.http.client.uri.UriComponents;
import com.ning.http.client.websocket.WebSocketUpgradeHandler;
import com.ning.http.multipart.MultipartBody;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.AuthenticatorUtils;
import com.ning.http.util.ProxyUtils;
import com.ning.http.util.SslUtils;

public class NettyAsyncHttpProvider extends SimpleChannelUpstreamHandler implements AsyncHttpProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyAsyncHttpProvider.class);

    public static final String GZIP_DEFLATE = HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE;

    public static final IOException REMOTELY_CLOSED_EXCEPTION = new IOException("Remotely Closed");
    static {
        REMOTELY_CLOSED_EXCEPTION.setStackTrace(new StackTraceElement[0]);
    }
    public static final String HTTP_HANDLER = "httpHandler";
    public static final String SSL_HANDLER = "sslHandler";
    public static final String HTTP_PROCESSOR = "httpProcessor";
    public static final String WS_PROCESSOR = "wsProcessor";

    private static final String HTTPS = "https";
    private static final String HTTP = "http";
    private static final String WEBSOCKET = "ws";
    private static final String WEBSOCKET_SSL = "wss";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final ClientBootstrap plainBootstrap;
    private final ClientBootstrap secureBootstrap;
    private final ClientBootstrap webSocketBootstrap;
    private final ClientBootstrap secureWebSocketBootstrap;
    private final AsyncHttpClientConfig config;
    private final AtomicBoolean isClose = new AtomicBoolean(false);
    private final ClientSocketChannelFactory socketChannelFactory;
    private final boolean allowReleaseSocketChannelFactory;

    private final ChannelManager channelManager;
    private final NettyAsyncHttpProviderConfig providerConfig;
    private final boolean disableZeroCopy;
    private static final NTLMEngine ntlmEngine = new NTLMEngine();
    private static SpnegoEngine spnegoEngine = null;
    private final Protocol httpProtocol = new HttpProtocol();
    private final Protocol webSocketProtocol = new WebSocketProtocol();
    private final boolean allowStopNettyTimer;
    private final Timer nettyTimer;
    private final long handshakeTimeoutInMillis;

    private static boolean isNTLM(List<String> auth) {
        return isNonEmpty(auth) && auth.get(0).startsWith("NTLM");
    }

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {

        if (config.getAsyncHttpProviderConfig() instanceof NettyAsyncHttpProviderConfig) {
            providerConfig = NettyAsyncHttpProviderConfig.class.cast(config.getAsyncHttpProviderConfig());
        } else {
            providerConfig = new NettyAsyncHttpProviderConfig();
        }

        if (config.getRequestCompressionLevel() > 0) {
            LOGGER.warn("Request was enabled but Netty actually doesn't support this feature");
        }

        // check if external NioClientSocketChannelFactory is defined
        if (providerConfig.getSocketChannelFactory() != null) {
            socketChannelFactory = providerConfig.getSocketChannelFactory();
            // cannot allow releasing shared channel factory
            allowReleaseSocketChannelFactory = false;

        } else {
            ExecutorService e = providerConfig.getBossExecutorService();
            if (e == null)
                e = Executors.newCachedThreadPool();
            int numWorkers = config.getIoThreadMultiplier() * Runtime.getRuntime().availableProcessors();
            LOGGER.trace("Number of application's worker threads is {}", numWorkers);
            socketChannelFactory = new NioClientSocketChannelFactory(e, config.executorService(), numWorkers);
            allowReleaseSocketChannelFactory = true;
        }

        allowStopNettyTimer = providerConfig.getNettyTimer() == null;
        nettyTimer = allowStopNettyTimer ? newNettyTimer() : providerConfig.getNettyTimer();

        handshakeTimeoutInMillis = providerConfig.getHandshakeTimeoutInMillis();

        plainBootstrap = new ClientBootstrap(socketChannelFactory);
        secureBootstrap = new ClientBootstrap(socketChannelFactory);
        webSocketBootstrap = new ClientBootstrap(socketChannelFactory);
        secureWebSocketBootstrap = new ClientBootstrap(socketChannelFactory);
        disableZeroCopy = providerConfig.isDisableZeroCopy();

        this.config = config;

        configureNetty();

        // This is dangerous as we can't catch a wrong typed ConnectionsPool
        ChannelPool cp = providerConfig.getChannelPool();
        if (cp == null && config.isAllowPoolingConnection()) {
            cp = new DefaultChannelPool(config, nettyTimer);
        } else if (cp == null) {
            cp = new NoopChannelPool();
        }
        this.channelManager = new ChannelManager(config, cp);
    }

    private Timer newNettyTimer() {
        HashedWheelTimer timer = new HashedWheelTimer();
        timer.start();
        return timer;
    }

    void configureNetty() {

        // FIXME why not do that for other bootstraps
        for (Entry<String, Object> entry : providerConfig.propertiesSet()) {
            plainBootstrap.setOption(entry.getKey(), entry.getValue());
        }

        DefaultChannelFuture.setUseDeadLockChecker(providerConfig.isUseDeadLockChecker());

        plainBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();

                pipeline.addLast(HTTP_HANDLER, createHttpClientCodec());

                if (config.isCompressionEnabled()) {
                    pipeline.addLast("inflater", new HttpContentDecompressor());
                }
                pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                pipeline.addLast(HTTP_PROCESSOR, NettyAsyncHttpProvider.this);
                return pipeline;
            }
        });

        webSocketBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            /* @Override */
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(HTTP_HANDLER, createHttpClientCodec());
                pipeline.addLast(WS_PROCESSOR, NettyAsyncHttpProvider.this);
                return pipeline;
            }
        });
    }

    SslHandler createSslHandler(String peerHost, int peerPort) throws GeneralSecurityException, IOException {
        SSLEngine sslEngine = SslUtils.getInstance().createClientSSLEngine(config, peerHost, peerPort);
        return handshakeTimeoutInMillis > 0 ? new SslHandler(sslEngine, getDefaultBufferPool(), false, nettyTimer, handshakeTimeoutInMillis)
                : new SslHandler(sslEngine);
    }

    void constructSSLPipeline(final NettyConnectListener<?> cl) {

        secureBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            /* @Override */
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(SSL_HANDLER, new SslInitializer(NettyAsyncHttpProvider.this));
                pipeline.addLast(HTTP_HANDLER, createHttpsClientCodec());

                if (config.isCompressionEnabled()) {
                    pipeline.addLast("inflater", new HttpContentDecompressor());
                }
                pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                pipeline.addLast(HTTP_PROCESSOR, NettyAsyncHttpProvider.this);
                return pipeline;
            }
        });

        secureWebSocketBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            /* @Override */
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast(SSL_HANDLER, new SslInitializer(NettyAsyncHttpProvider.this));
                pipeline.addLast(HTTP_HANDLER, createHttpsClientCodec());
                pipeline.addLast(WS_PROCESSOR, NettyAsyncHttpProvider.this);

                return pipeline;
            }
        });

        if (providerConfig != null) {
            for (Entry<String, Object> entry : providerConfig.propertiesSet()) {
                secureBootstrap.setOption(entry.getKey(), entry.getValue());
                secureWebSocketBootstrap.setOption(entry.getKey(), entry.getValue());
            }
        }
    }

    private Channel lookupInCache(UriComponents uri, ProxyServer proxy, ConnectionPoolKeyStrategy strategy) {
        final Channel channel = channelManager.poll(getPoolKey(uri, proxy, strategy));

        if (channel != null) {
            LOGGER.debug("Using cached Channel {}\n for uri {}\n", channel, uri);

            try {
                // Always make sure the channel who got cached support the proper protocol. It could
                // only occurs when a HttpMethod.CONNECT is used against a proxy that requires upgrading from http to
                // https.
                return verifyChannelPipeline(channel, uri.getScheme());
            } catch (Exception ex) {
                LOGGER.debug(ex.getMessage(), ex);
            }
        }
        return null;
    }

    private HttpClientCodec createHttpClientCodec() {
        return new HttpClientCodec(providerConfig.getHttpClientCodecMaxInitialLineLength(),//
                providerConfig.getHttpClientCodecMaxHeaderSize(),//
                providerConfig.getHttpClientCodecMaxChunkSize());
    }

    private HttpClientCodec createHttpsClientCodec() {
        return new HttpClientCodec(providerConfig.getHttpClientCodecMaxInitialLineLength(),//
                providerConfig.getHttpClientCodecMaxHeaderSize(),//
                providerConfig.getHttpClientCodecMaxChunkSize());
    }

    private Channel verifyChannelPipeline(Channel channel, String scheme) throws IOException, GeneralSecurityException {

        if (channel.getPipeline().get(SSL_HANDLER) != null && HTTP.equalsIgnoreCase(scheme)) {
            channel.getPipeline().remove(SSL_HANDLER);
        } else if (channel.getPipeline().get(HTTP_HANDLER) != null && HTTP.equalsIgnoreCase(scheme)) {
            return channel;
        } else if (channel.getPipeline().get(SSL_HANDLER) == null && isSecure(scheme)) {
            channel.getPipeline().addFirst(SSL_HANDLER, new SslInitializer(NettyAsyncHttpProvider.this));
        }
        return channel;
    }

    protected final <T> void writeRequest(final Channel channel, final AsyncHttpClientConfig config, final NettyResponseFuture<T> future) {

        HttpRequest nettyRequest = future.getNettyRequest();
        HttpHeaders nettyRequestHeaders = nettyRequest.headers();
        boolean ssl = channel.getPipeline().get(SslHandler.class) != null;

        try {
            /**
             * If the channel is dead because it was pooled and the remote server decided to close it, we just let it go and the channelClosed do it's work.
             */
            if (!channel.isOpen() || !channel.isConnected()) {
                return;
            }

            Body body = null;
            if (!nettyRequest.getMethod().equals(HttpMethod.CONNECT)) {
                BodyGenerator bg = future.getRequest().getBodyGenerator();

                if (bg == null && future.getRequest().getStreamData() != null) {
                    bg = new InputStreamBodyGenerator(future.getRequest().getStreamData());
                }

                if (bg != null) {
                    // Netty issue with chunking.
                    if (bg instanceof InputStreamBodyGenerator) {
                        InputStreamBodyGenerator.class.cast(bg).patchNettyChunkingIssue(true);
                    }

                    try {
                        body = bg.createBody();
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                    long length = body.getContentLength();
                    if (length >= 0) {
                        nettyRequestHeaders.set(HttpHeaders.Names.CONTENT_LENGTH, length);
                    } else {
                        nettyRequestHeaders.set(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
                    }

                } else if (isNonEmpty(future.getRequest().getParts())) {
                    String contentType = nettyRequestHeaders.get(HttpHeaders.Names.CONTENT_TYPE);
                    String contentLength = nettyRequestHeaders.get(HttpHeaders.Names.CONTENT_LENGTH);

                    long length = -1;
                    if (contentLength != null) {
                        length = Long.parseLong(contentLength);
                    } else {
                        nettyRequestHeaders.add(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
                    }

                    body = new MultipartBody(future.getRequest().getParts(), contentType, length);
                }
            }

            if (future.getAsyncHandler() instanceof TransferCompletionHandler) {

                FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
                for (String s : nettyRequestHeaders.names()) {
                    for (String header : nettyRequestHeaders.getAll(s)) {
                        h.add(s, header);
                    }
                }

                TransferCompletionHandler.class.cast(future.getAsyncHandler()).transferAdapter(
                        new NettyTransferAdapter(h, nettyRequest.getContent(), future.getRequest().getFile()));
            }

            // Leave it to true.
            if (future.getAndSetWriteHeaders(true)) {
                try {
                    if (future.getAsyncHandler() instanceof AsyncHandlerExtensions)
                        AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRequestSent();

                    channel.write(nettyRequest).addListener(new ProgressListener(true, future.getAsyncHandler(), future));
                } catch (Throwable cause) {
                    LOGGER.debug(cause.getMessage(), cause);
                    try {
                        channel.close();
                    } catch (RuntimeException ex) {
                        LOGGER.debug(ex.getMessage(), ex);
                    }
                    return;
                }
            }

            if (future.getAndSetWriteBody(true)) {
                if (!nettyRequest.getMethod().equals(HttpMethod.CONNECT)) {

                    if (future.getRequest().getFile() != null) {
                        final File file = future.getRequest().getFile();
                        final RandomAccessFile raf = new RandomAccessFile(file, "r");

                        try {
                            ChannelFuture writeFuture;
                            if (disableZeroCopy || ssl) {
                                writeFuture = channel
                                        .write(new ChunkedFile(raf, 0, raf.length(), providerConfig.getChunkedFileChunkSize()));
                            } else {
                                final FileRegion region = new OptimizedFileRegion(raf, 0, raf.length());
                                writeFuture = channel.write(region);
                            }
                            writeFuture.addListener(new ProgressListener(false, future.getAsyncHandler(), future) {
                                public void operationComplete(ChannelFuture cf) {
                                    try {
                                        raf.close();
                                    } catch (IOException e) {
                                        LOGGER.warn("Failed to close request body: {}", e.getMessage(), e);
                                    }
                                    super.operationComplete(cf);
                                }
                            });
                        } catch (IOException ex) {
                            if (raf != null) {
                                try {
                                    raf.close();
                                } catch (IOException e) {
                                }
                            }
                            throw ex;
                        }
                    } else if (body != null) {
                        final Body b = body;

                        ChannelFuture writeFuture;
                        if (disableZeroCopy || ssl || !(body instanceof RandomAccessBody)) {
                            BodyChunkedInput bodyChunkedInput = new BodyChunkedInput(body);
                            writeFuture = channel.write(bodyChunkedInput);
                        } else {
                            BodyFileRegion bodyFileRegion = new BodyFileRegion((RandomAccessBody) body);
                            writeFuture = channel.write(bodyFileRegion);
                        }
                        writeFuture.addListener(new ProgressListener(false, future.getAsyncHandler(), future) {
                            public void operationComplete(ChannelFuture cf) {
                                try {
                                    b.close();
                                } catch (IOException e) {
                                    LOGGER.warn("Failed to close request body: {}", e.getMessage(), e);
                                }
                                super.operationComplete(cf);
                            }
                        });
                    }
                }
            }
        } catch (Throwable ioe) {
            try {
                channel.close();
            } catch (RuntimeException ex) {
                LOGGER.debug(ex.getMessage(), ex);
            }
        }

        try {
            future.touch();
            int requestTimeoutInMs = AsyncHttpProviderUtils.requestTimeout(config, future.getRequest());
            TimeoutsHolder timeoutsHolder = new TimeoutsHolder();
            if (requestTimeoutInMs != -1) {
                Timeout requestTimeout = newTimeoutInMs(new RequestTimeoutTimerTask(future, this, timeoutsHolder), requestTimeoutInMs);
                timeoutsHolder.requestTimeout = requestTimeout;
            }

            int idleConnectionTimeoutInMs = config.getIdleConnectionTimeoutInMs();
            if (idleConnectionTimeoutInMs != -1 && idleConnectionTimeoutInMs <= requestTimeoutInMs) {
                // no need for a idleConnectionTimeout that's less than the requestTimeoutInMs
                Timeout idleConnectionTimeout = newTimeoutInMs(new IdleConnectionTimeoutTimerTask(future, this, timeoutsHolder,
                        requestTimeoutInMs, idleConnectionTimeoutInMs), idleConnectionTimeoutInMs);
                timeoutsHolder.idleConnectionTimeout = idleConnectionTimeout;
            }
            future.setTimeoutsHolder(timeoutsHolder);

        } catch (RejectedExecutionException ex) {
            abort(future, ex);
        }
    }

    protected static final HttpRequest buildRequest(AsyncHttpClientConfig config, Request request, UriComponents uri, boolean allowConnect,
            ChannelBuffer buffer, ProxyServer proxyServer) throws IOException {

        String method = request.getMethod();
        if (allowConnect && proxyServer != null && isSecure(uri)) {
            method = HttpMethod.CONNECT.toString();
        }
        return construct(config, request, new HttpMethod(method), uri, buffer, proxyServer);
    }

    private static SpnegoEngine getSpnegoEngine() {
        if (spnegoEngine == null)
            spnegoEngine = new SpnegoEngine();
        return spnegoEngine;
    }

    private static String computeNonConnectRequestPath(AsyncHttpClientConfig config, UriComponents uri, ProxyServer proxyServer) {
        if (proxyServer != null && !(isSecure(uri) && config.isUseRelativeURIsWithSSLProxies()))
            return uri.toString();
        else {
            String path = getNonEmptyPath(uri);
            return uri.getQuery() != null ? path + "?" + uri.getQuery() : path;
        }
    }

    private static HttpRequest construct(AsyncHttpClientConfig config, Request request, HttpMethod m, UriComponents uri,
            ChannelBuffer buffer, ProxyServer proxyServer) throws IOException {

        HttpRequest nettyRequest;

        if (m.equals(HttpMethod.CONNECT)) {
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_0, m, AsyncHttpProviderUtils.getAuthority(uri));
        } else {
            String path = computeNonConnectRequestPath(config, uri, proxyServer);
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, m, path);
        }

        HttpHeaders nettyRequestHeaders = nettyRequest.headers();

        boolean webSocket = isWebSocket(uri.getScheme());
        if (webSocket && !m.equals(HttpMethod.CONNECT)) {
            nettyRequestHeaders.add(HttpHeaders.Names.UPGRADE, HttpHeaders.Values.WEBSOCKET);
            nettyRequestHeaders.add(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.UPGRADE);
            nettyRequestHeaders.add(HttpHeaders.Names.ORIGIN, "http://" + uri.getHost() + ":" + uri.getPort());
            nettyRequestHeaders.add(HttpHeaders.Names.SEC_WEBSOCKET_KEY, WebSocketUtil.getKey());
            nettyRequestHeaders.add(HttpHeaders.Names.SEC_WEBSOCKET_VERSION, "13");
        }

        String host = request.getVirtualHost() != null ? request.getVirtualHost() : uri.getHost();
        if (host != null) {
            // FIXME why write port when regular host?
            if (request.getVirtualHost() != null || uri.getPort() == -1) {
                nettyRequestHeaders.set(HttpHeaders.Names.HOST, host);
            } else {
                nettyRequestHeaders.set(HttpHeaders.Names.HOST, host + ":" + uri.getPort());
            }
        } else {
            host = "127.0.0.1";
        }

        if (!m.equals(HttpMethod.CONNECT)) {
            for (Entry<String, List<String>> header : request.getHeaders()) {
                String name = header.getKey();
                if (!HttpHeaders.Names.HOST.equalsIgnoreCase(name)) {
                    for (String value : header.getValue()) {
                        nettyRequestHeaders.add(name, value);
                    }
                }
            }

            if (config.isCompressionEnabled()) {
                nettyRequestHeaders.set(HttpHeaders.Names.ACCEPT_ENCODING, GZIP_DEFLATE);
            }
        } else {
            List<String> auth = request.getHeaders().get(HttpHeaders.Names.PROXY_AUTHORIZATION);
            if (isNTLM(auth)) {
                nettyRequestHeaders.add(HttpHeaders.Names.PROXY_AUTHORIZATION, auth.get(0));
            }
        }
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

        if (realm != null && realm.getUsePreemptiveAuth()) {

            String domain = realm.getNtlmDomain();
            if (proxyServer != null && proxyServer.getNtlmDomain() != null) {
                domain = proxyServer.getNtlmDomain();
            }

            String authHost = realm.getNtlmHost();
            if (proxyServer != null && proxyServer.getHost() != null) {
                host = proxyServer.getHost();
            }

            switch (realm.getAuthScheme()) {
            case BASIC:
                nettyRequestHeaders.add(HttpHeaders.Names.AUTHORIZATION, AuthenticatorUtils.computeBasicAuthentication(realm));
                break;
            case DIGEST:
                if (isNonEmpty(realm.getNonce())) {
                    try {
                        nettyRequestHeaders.add(HttpHeaders.Names.AUTHORIZATION, AuthenticatorUtils.computeDigestAuthentication(realm));
                    } catch (NoSuchAlgorithmException e) {
                        throw new SecurityException(e);
                    }
                }
                break;
            case NTLM:
                try {
                    nettyRequestHeaders.add(HttpHeaders.Names.AUTHORIZATION, ntlmEngine.generateType1Msg("NTLM " + domain, authHost));
                } catch (NTLMEngineException e) {
                    IOException ie = new IOException();
                    ie.initCause(e);
                    throw ie;
                }
                break;
            case KERBEROS:
            case SPNEGO:
                String challengeHeader = null;
                String server = proxyServer == null ? host : proxyServer.getHost();
                try {
                    challengeHeader = getSpnegoEngine().generateToken(server);
                } catch (Throwable e) {
                    IOException ie = new IOException();
                    ie.initCause(e);
                    throw ie;
                }
                nettyRequestHeaders.add(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);
                break;
            case NONE:
                break;
            default:
                throw new IllegalStateException(String.format("Invalid Authentication %s", realm.toString()));
            }
        }

        if (!webSocket && !request.getHeaders().containsKey(HttpHeaders.Names.CONNECTION)) {
            nettyRequestHeaders.set(HttpHeaders.Names.CONNECTION, AsyncHttpProviderUtils.keepAliveHeaderValue(config));
        }

        if (proxyServer != null) {
            if (!request.getHeaders().containsKey("Proxy-Connection")) {
                nettyRequestHeaders.set("Proxy-Connection", AsyncHttpProviderUtils.keepAliveHeaderValue(config));
            }

            if (proxyServer.getPrincipal() != null) {
                if (isNonEmpty(proxyServer.getNtlmDomain())) {

                    List<String> auth = request.getHeaders().get(HttpHeaders.Names.PROXY_AUTHORIZATION);
                    if (!isNTLM(auth)) {
                        try {
                            String msg = ntlmEngine.generateType1Msg(proxyServer.getNtlmDomain(), proxyServer.getHost());
                            nettyRequestHeaders.set(HttpHeaders.Names.PROXY_AUTHORIZATION, "NTLM " + msg);
                        } catch (NTLMEngineException e) {
                            IOException ie = new IOException();
                            ie.initCause(e);
                            throw ie;
                        }
                    }
                } else {
                    nettyRequestHeaders.set(HttpHeaders.Names.PROXY_AUTHORIZATION,
                            AuthenticatorUtils.computeBasicAuthentication(proxyServer));
                }
            }
        }

        // Add default accept headers.
        if (!request.getHeaders().containsKey(HttpHeaders.Names.ACCEPT)) {
            nettyRequestHeaders.set(HttpHeaders.Names.ACCEPT, "*/*");
        }

        String userAgentHeader = request.getHeaders().getFirstValue(HttpHeaders.Names.USER_AGENT);
        if (userAgentHeader != null) {
            nettyRequestHeaders.set(HttpHeaders.Names.USER_AGENT, userAgentHeader);
        } else if (config.getUserAgent() != null) {
            nettyRequestHeaders.set(HttpHeaders.Names.USER_AGENT, config.getUserAgent());
        } else {
            nettyRequestHeaders.set(HttpHeaders.Names.USER_AGENT, AsyncHttpProviderUtils.constructUserAgent(NettyAsyncHttpProvider.class));
        }

        if (!m.equals(HttpMethod.CONNECT)) {
            if (isNonEmpty(request.getCookies())) {
                nettyRequestHeaders.set(HttpHeaders.Names.COOKIE, CookieEncoder.encode(request.getCookies()));
            }

            String bodyCharset = request.getBodyEncoding() == null ? DEFAULT_CHARSET : request.getBodyEncoding();

            // We already have processed the body.
            if (buffer != null && buffer.writerIndex() != 0) {
                nettyRequestHeaders.set(HttpHeaders.Names.CONTENT_LENGTH, buffer.writerIndex());
                nettyRequest.setContent(buffer);

            } else if (request.getByteData() != null) {
                nettyRequestHeaders.set(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getByteData().length));
                nettyRequest.setContent(ChannelBuffers.wrappedBuffer(request.getByteData()));

            } else if (request.getStringData() != null) {
                byte[] bytes = request.getStringData().getBytes(bodyCharset);
                nettyRequestHeaders.set(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(bytes.length));
                nettyRequest.setContent(ChannelBuffers.wrappedBuffer(bytes));

            } else if (isNonEmpty(request.getFormParams())) {
                String formBody = AsyncHttpProviderUtils.formParams2UTF8String(request.getFormParams());
                nettyRequestHeaders.set(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(formBody.length()));
                nettyRequest.setContent(ChannelBuffers.wrappedBuffer(formBody.getBytes(bodyCharset)));

                if (!request.getHeaders().containsKey(HttpHeaders.Names.CONTENT_TYPE)) {
                    nettyRequestHeaders.set(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
                }

            } else if (isNonEmpty(request.getParts())) {
                MultipartRequestEntity mre = AsyncHttpProviderUtils.createMultipartRequestEntity(request.getParts(), request.getHeaders());

                nettyRequestHeaders.set(HttpHeaders.Names.CONTENT_TYPE, mre.getContentType());
                long contentLength = mre.getContentLength();
                if (contentLength >= 0) {
                    nettyRequestHeaders.set(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(contentLength));
                }

            } else if (request.getFile() != null) {
                File file = request.getFile();
                if (!file.isFile()) {
                    throw new IOException(String.format("File %s is not a file or doesn't exist", file.getAbsolutePath()));
                }
                nettyRequestHeaders.set(HttpHeaders.Names.CONTENT_LENGTH, file.length());
            }
        }
        return nettyRequest;
    }

    public void close() {
        if (isClose.compareAndSet(false, true)) {
            try {
                channelManager.destroy();

                config.executorService().shutdown();
                if (allowReleaseSocketChannelFactory) {
                    socketChannelFactory.releaseExternalResources();
                    plainBootstrap.releaseExternalResources();
                    secureBootstrap.releaseExternalResources();
                    webSocketBootstrap.releaseExternalResources();
                    secureWebSocketBootstrap.releaseExternalResources();
                }

                if (allowStopNettyTimer)
                    nettyTimer.stop();

            } catch (Throwable t) {
                LOGGER.warn("Unexpected error on close", t);
            }
        }
    }

    /* @Override */

    public Response prepareResponse(final HttpResponseStatus status, final HttpResponseHeaders headers,
            final List<HttpResponseBodyPart> bodyParts) {
        return new NettyResponse(status, headers, bodyParts);
    }

    /* @Override */

    public <T> ListenableFuture<T> execute(Request request, final AsyncHandler<T> asyncHandler) throws IOException {
        return doConnect(request, asyncHandler, null, true, false);
    }

    private <T> void execute(final Request request, final NettyResponseFuture<T> f, boolean useCache, boolean reclaimCache)
            throws IOException {
        doConnect(request, f.getAsyncHandler(), f, useCache, reclaimCache);
    }

    private <T> NettyResponseFuture<T> buildNettyResponseFutureWithCachedChannel(Request request, AsyncHandler<T> asyncHandler,
            NettyResponseFuture<T> f, ProxyServer proxyServer, UriComponents uri, ChannelBuffer bufferedBytes, int maxTry)
            throws IOException {

        for (int i = 0; i < maxTry; i++) {
            if (maxTry == 0)
                return null;

            Channel channel = null;
            if (f != null && f.reuseChannel() && f.channel() != null) {
                channel = f.channel();
            } else {
                channel = lookupInCache(uri, proxyServer, request.getConnectionPoolKeyStrategy());
            }

            if (channel == null)
                return null;
            else {
                HttpRequest nettyRequest = null;

                if (f == null) {
                    nettyRequest = buildRequest(config, request, uri, false, bufferedBytes, proxyServer);
                    f = newFuture(uri, request, asyncHandler, nettyRequest, config, this, proxyServer);
                } else if (i == 0) {
                    // only build request on first try
                    nettyRequest = buildRequest(config, request, uri, f.isConnectAllowed(), bufferedBytes, proxyServer);
                    f.setNettyRequest(nettyRequest);
                }
                f.setState(NettyResponseFuture.STATE.POOLED);
                f.attachChannel(channel, false);

                if (channel.isOpen() && channel.isConnected()) {
                    Channels.setAttachment(channel, f);
                    return f;
                } else
                    // else, channel was closed by the server since we fetched it from the pool, starting over
                    f.attachChannel(null);
            }
        }
        return null;
    }

    private <T> NettyResponseFuture<T> buildConnectListenerFuture(AsyncHttpClientConfig config,//
            Request request,//
            AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//
            NettyAsyncHttpProvider provider,//
            ChannelBuffer buffer,//
            UriComponents uri) throws IOException {
        ProxyServer proxyServer = ProxyUtils.getProxyServer(config, request);
        HttpRequest nettyRequest = NettyAsyncHttpProvider.buildRequest(config, request, uri, true, buffer, proxyServer);
        if (future == null) {
            return NettyAsyncHttpProvider.newFuture(uri, request, asyncHandler, nettyRequest, config, provider, proxyServer);
        } else {
            future.setNettyRequest(nettyRequest);
            future.setRequest(request);
            return future;
        }
    }

    private <T> ListenableFuture<T> doConnect(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> f,
            boolean useCache, boolean reclaimCache) throws IOException {

        if (isClose()) {
            throw new IOException("Closed");
        }

        UriComponents uri = request.getURI();

        if (uri.getScheme().startsWith(WEBSOCKET) && !validateWebSocketRequest(request, asyncHandler)) {
            throw new IOException("WebSocket method must be a GET");
        }

        ProxyServer proxyServer = ProxyUtils.getProxyServer(config, request);

        boolean resultOfAConnect = f != null && f.getNettyRequest() != null && f.getNettyRequest().getMethod().equals(HttpMethod.CONNECT);
        boolean useProxy = proxyServer != null && !resultOfAConnect;

        ChannelBuffer bufferedBytes = null;
        if (f != null && f.getRequest().getFile() == null
                && !f.getNettyRequest().getMethod().getName().equals(HttpMethod.CONNECT.getName())) {
            bufferedBytes = f.getNettyRequest().getContent();
        }

        boolean useSSl = isSecure(uri) && !useProxy;

        if (useCache) {
            // 3 tentatives
            NettyResponseFuture<T> connectedFuture = buildNettyResponseFutureWithCachedChannel(request, asyncHandler, f, proxyServer, uri,
                    bufferedBytes, 3);

            if (connectedFuture != null) {
                LOGGER.debug("\nUsing cached Channel {}\n for request \n{}\n", connectedFuture.channel(), connectedFuture.getNettyRequest());

                try {
                    writeRequest(connectedFuture.channel(), config, connectedFuture);
                } catch (Exception ex) {
                    LOGGER.debug("writeRequest failure", ex);
                    if (useSSl && ex.getMessage() != null && ex.getMessage().contains("SSLEngine")) {
                        LOGGER.debug("SSLEngine failure", ex);
                        connectedFuture = null;
                    } else {
                        try {
                            asyncHandler.onThrowable(ex);
                        } catch (Throwable t) {
                            LOGGER.warn("doConnect.writeRequest()", t);
                        }
                        IOException ioe = new IOException(ex.getMessage());
                        ioe.initCause(ex);
                        throw ioe;
                    }
                }
                return connectedFuture;
            }
        }

        NettyResponseFuture<T> connectListenerFuture = buildConnectListenerFuture(config, request, asyncHandler, f, this, bufferedBytes,
                uri);

        boolean channelPreempted = false;
        String poolKey = null;

        // Do not throw an exception when we need an extra connection for a redirect.
        if (!reclaimCache) {

            // only compute when maxConnectionPerHost is enabled
            // FIXME clean up
            if (config.getMaxConnectionPerHost() > 0)
                poolKey = getPoolKey(connectListenerFuture);

            if (channelManager.preemptChannel(poolKey)) {
                channelPreempted = true;
            } else {
                IOException ex = new IOException(String.format("Too many connections %s", config.getMaxTotalConnections()));
                try {
                    asyncHandler.onThrowable(ex);
                } catch (Exception e) {
                    LOGGER.warn("asyncHandler.onThrowable crashed", e);
                }
                throw ex;
            }
        }

        NettyConnectListener<T> connectListener = new NettyConnectListener<T>(config, connectListenerFuture, this, channelManager,
                channelPreempted, poolKey);

        if (useSSl)
            constructSSLPipeline(connectListener);

        ChannelFuture channelFuture;
        ClientBootstrap bootstrap = (request.getURI().getScheme().startsWith(WEBSOCKET) && !useProxy) ? (useSSl ? secureWebSocketBootstrap
                : webSocketBootstrap) : (useSSl ? secureBootstrap : plainBootstrap);
        bootstrap.setOption("connectTimeoutMillis", config.getConnectionTimeoutInMs());

        try {
            InetSocketAddress remoteAddress;
            if (request.getInetAddress() != null) {
                remoteAddress = new InetSocketAddress(request.getInetAddress(), AsyncHttpProviderUtils.getDefaultPort(uri));
            } else if (!useProxy) {
                remoteAddress = new InetSocketAddress(uri.getHost(), AsyncHttpProviderUtils.getDefaultPort(uri));
            } else {
                remoteAddress = new InetSocketAddress(proxyServer.getHost(), proxyServer.getPort());
            }

            if (request.getLocalAddress() != null) {
                channelFuture = bootstrap.connect(remoteAddress, new InetSocketAddress(request.getLocalAddress(), 0));
            } else {
                channelFuture = bootstrap.connect(remoteAddress);
            }

            channelFuture.addListener(connectListener);

        } catch (Throwable t) {
            if (channelPreempted)
                channelManager.abortChannelPreemption(poolKey);
            abort(connectListener.future(), t.getCause() == null ? t : t.getCause());
        }

        return connectListener.future();
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, MessageEvent e) throws Exception {

        // call super to reset the read timeout
        super.messageReceived(ctx, e);

        Channel channel = ctx.getChannel();
        Object attachment = Channels.getAttachment(channel);

        if (attachment == null)
            LOGGER.debug("ChannelHandlerContext doesn't have any attachment");

        if (attachment == DiscardEvent.INSTANCE) {
            // discard

        } else if (attachment instanceof AsyncCallable) {
            Object message = e.getMessage();
            AsyncCallable ac = (AsyncCallable) attachment;
            if (message instanceof HttpChunk) {
                // the AsyncCallable is to be processed on the last chunk
                if (HttpChunk.class.cast(message).isLast())
                    // process the AsyncCallable before passing the message to the protocol
                    ac.call();
            } else {
                ac.call();
                Channels.setDiscard(channel);
            }

        } else if (attachment instanceof NettyResponseFuture<?>) {
            Protocol p = (ctx.getPipeline().get(HTTP_PROCESSOR) != null ? httpProtocol : webSocketProtocol);
            p.handle(channel, e, NettyResponseFuture.class.cast(attachment));

        } else {
            // unhandled message
            try {
                ctx.getChannel().close();
            } catch (Throwable t) {
                LOGGER.trace("Closing an orphan channel {}", ctx.getChannel());
            }
        }
    }

    private Realm kerberosChallenge(List<String> proxyAuth, Request request, ProxyServer proxyServer,
            FluentCaseInsensitiveStringsMap headers, Realm realm, NettyResponseFuture<?> future, boolean proxyInd)
            throws NTLMEngineException {

        UriComponents uri = request.getURI();
        String host = request.getVirtualHost() != null ? request.getVirtualHost() : uri.getHost();
        String server = proxyServer == null ? host : proxyServer.getHost();
        try {
            String challengeHeader = getSpnegoEngine().generateToken(server);
            headers.remove(HttpHeaders.Names.AUTHORIZATION);
            headers.add(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);

            Realm.RealmBuilder realmBuilder;
            if (realm != null) {
                realmBuilder = new Realm.RealmBuilder().clone(realm);
            } else {
                realmBuilder = new Realm.RealmBuilder();
            }
            return realmBuilder.setUri(uri).setMethodName(request.getMethod()).setScheme(Realm.AuthScheme.KERBEROS).build();
        } catch (Throwable throwable) {
            if (isNTLM(proxyAuth))
                return ntlmChallenge(proxyAuth, request, proxyServer, headers, realm, future, proxyInd);
            abort(future, throwable);
            return null;
        }
    }

    private String authorizationHeaderName(boolean proxyInd) {
        return proxyInd ? HttpHeaders.Names.PROXY_AUTHORIZATION : HttpHeaders.Names.AUTHORIZATION;
    }

    private void addNTLMAuthorization(FluentCaseInsensitiveStringsMap headers, String challengeHeader, boolean proxyInd) {
        headers.add(authorizationHeaderName(proxyInd), "NTLM " + challengeHeader);
    }

    private void addType3NTLMAuthorizationHeader(List<String> auth, FluentCaseInsensitiveStringsMap headers, String username,
            String password, String domain, String workstation, boolean proxyInd) throws NTLMEngineException {
        headers.remove(authorizationHeaderName(proxyInd));

        // Beware of space!, see #462
        if (isNonEmpty(auth) && auth.get(0).startsWith("NTLM ")) {
            String serverChallenge = auth.get(0).trim().substring("NTLM ".length());
            String challengeHeader = ntlmEngine.generateType3Msg(username, password, domain, workstation, serverChallenge);
            addNTLMAuthorization(headers, challengeHeader, proxyInd);
        }
    }

    private Realm ntlmChallenge(List<String> wwwAuth, Request request, ProxyServer proxyServer, FluentCaseInsensitiveStringsMap headers,
            Realm realm, NettyResponseFuture<?> future, boolean proxyInd) throws NTLMEngineException {

        boolean useRealm = (proxyServer == null && realm != null);

        String ntlmDomain = useRealm ? realm.getNtlmDomain() : proxyServer.getNtlmDomain();
        String ntlmHost = useRealm ? realm.getNtlmHost() : proxyServer.getHost();
        String principal = useRealm ? realm.getPrincipal() : proxyServer.getPrincipal();
        String password = useRealm ? realm.getPassword() : proxyServer.getPassword();
        UriComponents uri = request.getURI();

        Realm.RealmBuilder realmBuilder;
        if (realm != null && !realm.isNtlmMessageType2Received()) {
            String challengeHeader = ntlmEngine.generateType1Msg(ntlmDomain, ntlmHost);
            addNTLMAuthorization(headers, challengeHeader, proxyInd);
            realmBuilder = new Realm.RealmBuilder().clone(realm).setScheme(realm.getAuthScheme()).setNtlmMessageType2Received(true);
            future.getAndSetAuth(false);
        } else {
            addType3NTLMAuthorizationHeader(wwwAuth, headers, principal, password, ntlmDomain, ntlmHost, proxyInd);

            if (realm != null) {
                realmBuilder = new Realm.RealmBuilder().clone(realm).setScheme(realm.getAuthScheme());
            } else {
                realmBuilder = new Realm.RealmBuilder().setScheme(Realm.AuthScheme.NTLM);
            }
        }

        return realmBuilder.setUri(uri).setMethodName(request.getMethod()).build();
    }

    private Realm ntlmProxyChallenge(List<String> wwwAuth, Request request, ProxyServer proxyServer,
            FluentCaseInsensitiveStringsMap headers, Realm realm, NettyResponseFuture<?> future) throws NTLMEngineException {
        future.getAndSetAuth(false);

        addType3NTLMAuthorizationHeader(wwwAuth, headers, proxyServer.getPrincipal(), proxyServer.getPassword(),
                proxyServer.getNtlmDomain(), proxyServer.getHost(), true);

        Realm.RealmBuilder realmBuilder = new Realm.RealmBuilder();
        if (realm != null) {
            realmBuilder = realmBuilder.clone(realm);
        }
        return realmBuilder.setUri(request.getURI()).setMethodName(request.getMethod()).build();
    }

    private String getPoolKey(NettyResponseFuture<?> future) {
        return getPoolKey(future.getURI(), future.getProxyServer(), future.getConnectionPoolKeyStrategy());
    }

    private String getPoolKey(UriComponents uri, ProxyServer proxy, ConnectionPoolKeyStrategy strategy) {
        String serverPart = strategy.getKey(uri);
        return proxy != null ? proxy.getUrl() + serverPart : serverPart;
    }

    private void drainChannel(final Channel channel, final NettyResponseFuture<?> future) {
        Channels.setAttachment(channel, newDrainCallable(future, channel, future.getKeepAlive(), getPoolKey(future)));
    }

    private FilterContext<?> handleIoException(FilterContext<?> fc, NettyResponseFuture<?> future) {
        for (IOExceptionFilter asyncFilter : config.getIOExceptionFilters()) {
            try {
                fc = asyncFilter.filter(fc);
                if (fc == null) {
                    throw new NullPointerException("FilterContext is null");
                }
            } catch (FilterException efe) {
                abort(future, efe);
            }
        }
        return fc;
    }

    private void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, Channel channel) throws IOException {
        if (future.getAsyncHandler() instanceof AsyncHandlerExtensions) {
            AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();
        }
        final Request newRequest = fc.getRequest();
        future.setAsyncHandler(fc.getAsyncHandler());
        future.setState(NettyResponseFuture.STATE.NEW);
        future.touch();

        LOGGER.debug("\n\nReplaying Request {}\n for Future {}\n", newRequest, future);
        drainChannel(channel, future);
        nextRequest(newRequest, future);
        return;
    }

    private List<String> getNettyHeaderValuesByCaseInsensitiveName(HttpHeaders headers, String name) {
        ArrayList<String> l = new ArrayList<String>();
        for (Entry<String, String> e : headers) {
            if (e.getKey().equalsIgnoreCase(name)) {
                l.add(e.getValue().trim());
            }
        }
        return l;
    }

    private void nextRequest(final Request request, final NettyResponseFuture<?> future) throws IOException {
        nextRequest(request, future, true);
    }

    private void nextRequest(final Request request, final NettyResponseFuture<?> future, final boolean useCache) throws IOException {
        execute(request, future, useCache, true);
    }

    public void abort(NettyResponseFuture<?> future, Throwable t) {
        Channel channel = future.channel();
        if (channel != null)
            channelManager.closeChannel(channel);

        if (!future.isDone()) {
            LOGGER.debug("Aborting Future {}\n", future);
            LOGGER.debug(t.getMessage(), t);
        }

        future.abort(t);
    }

    private void upgradeProtocol(ChannelPipeline p, String scheme, String host, int port) throws IOException, GeneralSecurityException {
        if (p.get(HTTP_HANDLER) != null) {
            p.remove(HTTP_HANDLER);
        }

        if (isSecure(scheme)) {
            if (p.get(SSL_HANDLER) == null) {
                p.addFirst(HTTP_HANDLER, createHttpClientCodec());
                p.addFirst(SSL_HANDLER, createSslHandler(host, port));
            } else {
                p.addAfter(SSL_HANDLER, HTTP_HANDLER, createHttpClientCodec());
            }

        } else {
            p.addFirst(HTTP_HANDLER, createHttpClientCodec());
        }

        if (isWebSocket(scheme)) {
            p.replace(HTTP_PROCESSOR, WS_PROCESSOR, NettyAsyncHttpProvider.this);
        }
    }

    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {

        if (isClose()) {
            return;
        }

        Channel channel = ctx.getChannel();
        channelManager.removeAll(channel);

        try {
            super.channelClosed(ctx, e);
        } catch (Exception ex) {
            LOGGER.trace("super.channelClosed", ex);
        }

        Object attachment = Channels.getAttachment(channel);
        LOGGER.debug("Channel Closed: {} with attachment {}", channel, attachment);

        if (attachment instanceof AsyncCallable) {
            AsyncCallable ac = (AsyncCallable) attachment;
            Channels.setAttachment(channel, ac.future());
            ac.call();

        } else if (attachment instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) attachment;
            future.touch();

            if (!config.getIOExceptionFilters().isEmpty()) {
                FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler())
                        .request(future.getRequest()).ioException(new IOException("Channel Closed")).build();
                fc = handleIoException(fc, future);

                if (fc.replayRequest() && future.canBeReplay()) {
                    replayRequest(future, fc, channel);
                    return;
                }
            }

            Protocol p = (ctx.getPipeline().get(HttpClientCodec.class) != null ? httpProtocol : webSocketProtocol);
            p.onClose(channel, e);

            if (future == null || future.isDone())
                channelManager.closeChannel(channel);

            else if (remotelyClosed(ctx.getChannel(), future))
                abort(future, REMOTELY_CLOSED_EXCEPTION);
        }
    }

    protected boolean remotelyClosed(Channel channel, NettyResponseFuture<?> future) {

        if (isClose())
            return true;

        if (future == null) {
            Object attachment = Channels.getAttachment(channel);
            if (attachment instanceof NettyResponseFuture)
                future = (NettyResponseFuture<?>) attachment;
        }

        if (future != null && future.canBeReplay()) {
            future.setState(NettyResponseFuture.STATE.RECONNECTED);

            LOGGER.debug("Trying to recover request {}\n", future.getNettyRequest());
            if (future.getAsyncHandler() instanceof AsyncHandlerExtensions)
                AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();

            try {
                nextRequest(future.getRequest(), future);
                return false;

            } catch (IOException iox) {
                future.setState(NettyResponseFuture.STATE.CLOSED);
                future.abort(iox);
                LOGGER.error("Remotely Closed, unable to recover", iox);
                return true;
            }

        } else {
            LOGGER.debug("Unable to recover future {}\n", future);
            return true;
        }
    }

    private void markAsDone(final NettyResponseFuture<?> future, final Channel channel) throws MalformedURLException {
        // We need to make sure everything is OK before adding the connection back to the pool.
        try {
            future.done();
        } catch (Throwable t) {
            // Never propagate exception once we know we are done.
            LOGGER.debug(t.getMessage(), t);
        }

        if (!future.getKeepAlive() || !channel.isReadable()) {
            channelManager.closeChannel(channel);
        }
    }

    private void finishUpdate(final NettyResponseFuture<?> future, Channel channel, boolean expectOtherChunks) throws IOException {
        boolean keepAlive = future.getKeepAlive();
        if (expectOtherChunks && keepAlive)
            drainChannel(channel, future);
        else
            channelManager.tryToOfferChannelToPool(channel, keepAlive, getPoolKey(future));
        markAsDone(future, channel);
    }

    private final boolean updateStatusAndInterrupt(AsyncHandler<?> handler, HttpResponseStatus c) throws Exception {
        return handler.onStatusReceived(c) != STATE.CONTINUE;
    }

    private final boolean updateHeadersAndInterrupt(AsyncHandler<?> handler, HttpResponseHeaders c) throws Exception {
        return handler.onHeadersReceived(c) != STATE.CONTINUE;
    }

    private final boolean updateBodyAndInterrupt(final NettyResponseFuture<?> future, AsyncHandler<?> handler, HttpResponseBodyPart c)
            throws Exception {
        boolean state = handler.onBodyPartReceived(c) != STATE.CONTINUE;
        if (c.closeUnderlyingConnection())
            future.setKeepAlive(false);
        return state;
    }

    // Simple marker for stopping publishing bytes.

    enum DiscardEvent {
        INSTANCE
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        Channel channel = ctx.getChannel();
        Throwable cause = e.getCause();
        NettyResponseFuture<?> future = null;

        if (e.getCause() instanceof PrematureChannelClosureException) {
            return;
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Unexpected I/O exception on channel {}", channel, cause);
        }

        try {
            if (cause instanceof ClosedChannelException) {
                return;
            }

            Object attachment = Channels.getAttachment(channel);
            if (attachment instanceof NettyResponseFuture<?>) {
                future = (NettyResponseFuture<?>) attachment;
                future.attachChannel(null, false);
                future.touch();

                if (cause instanceof IOException) {

                    if (!config.getIOExceptionFilters().isEmpty()) {
                        FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler())
                                .request(future.getRequest()).ioException(new IOException("Channel Closed")).build();
                        fc = handleIoException(fc, future);

                        if (fc.replayRequest()) {
                            replayRequest(future, fc, channel);
                            return;
                        }
                    } else {
                        // Close the channel so the recovering can occurs.
                        try {
                            channel.close();
                        } catch (Throwable t) {
                            // Swallow.
                        }
                        return;
                    }
                }

                if (abortOnReadCloseException(cause) || abortOnWriteCloseException(cause)) {
                    LOGGER.debug("Trying to recover from dead Channel: {}", channel);
                    return;
                }
            } else if (attachment instanceof AsyncCallable) {
                future = ((AsyncCallable) attachment).future();
            }
        } catch (Throwable t) {
            cause = t;
        }

        if (future != null) {
            try {
                LOGGER.debug("Was unable to recover Future: {}", future);
                abort(future, cause);
            } catch (Throwable t) {
                LOGGER.error(t.getMessage(), t);
            }
        }

        Protocol p = channel.getPipeline().get(HttpClientCodec.class) != null ? httpProtocol : webSocketProtocol;
        p.onError(channel, e);

        channelManager.closeChannel(channel);
        ctx.sendUpstream(e);
    }

    protected static boolean abortOnConnectCloseException(Throwable cause) {
        try {
            for (StackTraceElement element : cause.getStackTrace()) {
                if (element.getClassName().equals("sun.nio.ch.SocketChannelImpl") && element.getMethodName().equals("checkConnect")) {
                    return true;
                }
            }

            if (cause.getCause() != null) {
                return abortOnConnectCloseException(cause.getCause());
            }

        } catch (Throwable t) {
        }
        return false;
    }

    protected static boolean abortOnDisconnectException(Throwable cause) {
        try {
            for (StackTraceElement element : cause.getStackTrace()) {
                if (element.getClassName().equals("org.jboss.netty.handler.ssl.SslHandler")
                        && element.getMethodName().equals("channelDisconnected")) {
                    return true;
                }
            }

            if (cause.getCause() != null) {
                return abortOnConnectCloseException(cause.getCause());
            }

        } catch (Throwable t) {
        }
        return false;
    }

    protected static boolean abortOnReadCloseException(Throwable cause) {

        for (StackTraceElement element : cause.getStackTrace()) {
            if (element.getClassName().equals("sun.nio.ch.SocketDispatcher") && element.getMethodName().equals("read")) {
                return true;
            }
        }

        if (cause.getCause() != null) {
            return abortOnReadCloseException(cause.getCause());
        }

        return false;
    }

    protected static boolean abortOnWriteCloseException(Throwable cause) {

        for (StackTraceElement element : cause.getStackTrace()) {
            if (element.getClassName().equals("sun.nio.ch.SocketDispatcher") && element.getMethodName().equals("write")) {
                return true;
            }
        }

        if (cause.getCause() != null) {
            return abortOnWriteCloseException(cause.getCause());
        }

        return false;
    }

    public static <T> NettyResponseFuture<T> newFuture(UriComponents uri, Request request, AsyncHandler<T> asyncHandler,
            HttpRequest nettyRequest, AsyncHttpClientConfig config, NettyAsyncHttpProvider provider, ProxyServer proxyServer) {

        NettyResponseFuture<T> f = new NettyResponseFuture<T>(uri,//
                request,//
                asyncHandler,//
                nettyRequest,//
                AsyncHttpProviderUtils.requestTimeout(config, request),//
                config.getIdleConnectionTimeoutInMs(),//
                provider,//
                request.getConnectionPoolKeyStrategy(),//
                proxyServer);

        String expectHeader = request.getHeaders().getFirstValue(HttpHeaders.Names.EXPECT);
        if (expectHeader != null && expectHeader.equalsIgnoreCase(HttpHeaders.Values.CONTINUE)) {
            f.getAndSetWriteBody(false);
        }
        return f;
    }

    private class ProgressListener implements ChannelFutureProgressListener {

        private final boolean notifyHeaders;
        private final AsyncHandler<?> asyncHandler;
        private final NettyResponseFuture<?> future;

        public ProgressListener(boolean notifyHeaders, AsyncHandler<?> asyncHandler, NettyResponseFuture<?> future) {
            this.notifyHeaders = notifyHeaders;
            this.asyncHandler = asyncHandler;
            this.future = future;
        }

        public void operationComplete(ChannelFuture cf) {
            // The write operation failed. If the channel was cached, it means it got asynchronously closed.
            // Let's retry a second time.
            Throwable cause = cf.getCause();
            if (cause != null && future.getState() != NettyResponseFuture.STATE.NEW) {

                if (cause instanceof IllegalStateException) {
                    LOGGER.debug(cause.getMessage(), cause);
                    try {
                        cf.getChannel().close();
                    } catch (RuntimeException ex) {
                        LOGGER.debug(ex.getMessage(), ex);
                    }
                    return;
                }

                if (cause instanceof ClosedChannelException || abortOnReadCloseException(cause) || abortOnWriteCloseException(cause)) {

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(cf.getCause() == null ? "" : cf.getCause().getMessage(), cf.getCause());
                    }

                    try {
                        cf.getChannel().close();
                    } catch (RuntimeException ex) {
                        LOGGER.debug(ex.getMessage(), ex);
                    }
                    return;
                } else {
                    future.abort(cause);
                }
                return;
            }
            future.touch();

            /**
             * We need to make sure we aren't in the middle of an authorization process before publishing events as we will re-publish again the same event after the authorization,
             * causing unpredictable behavior.
             */
            Realm realm = future.getRequest().getRealm() != null ? future.getRequest().getRealm() : NettyAsyncHttpProvider.this.getConfig()
                    .getRealm();
            boolean startPublishing = future.isInAuth() || realm == null || realm.getUsePreemptiveAuth();

            if (startPublishing && asyncHandler instanceof ProgressAsyncHandler) {
                if (notifyHeaders) {
                    ProgressAsyncHandler.class.cast(asyncHandler).onHeaderWriteCompleted();
                } else {
                    ProgressAsyncHandler.class.cast(asyncHandler).onContentWriteCompleted();
                }
            }
        }

        public void operationProgressed(ChannelFuture cf, long amount, long current, long total) {
            future.touch();
            if (asyncHandler instanceof ProgressAsyncHandler) {
                ProgressAsyncHandler.class.cast(asyncHandler).onContentWriteProgress(amount, current, total);
            }
        }
    }

    private abstract class AsyncCallable implements Callable<Object> {

        private final NettyResponseFuture<?> future;

        public AsyncCallable(NettyResponseFuture<?> future) {
            this.future = future;
        }

        abstract public Object call() throws Exception;

        public NettyResponseFuture<?> future() {
            return future;
        }
    }

    public static class ThreadLocalBoolean extends ThreadLocal<Boolean> {

        private final boolean defaultValue;

        public ThreadLocalBoolean() {
            this(false);
        }

        public ThreadLocalBoolean(boolean defaultValue) {
            this.defaultValue = defaultValue;
        }

        @Override
        protected Boolean initialValue() {
            return defaultValue ? Boolean.TRUE : Boolean.FALSE;
        }
    }

    public static class OptimizedFileRegion implements FileRegion {

        private final FileChannel file;
        private final RandomAccessFile raf;
        private final long position;
        private final long count;
        private long byteWritten;

        public OptimizedFileRegion(RandomAccessFile raf, long position, long count) {
            this.raf = raf;
            this.file = raf.getChannel();
            this.position = position;
            this.count = count;
        }

        public long getPosition() {
            return position;
        }

        public long getCount() {
            return count;
        }

        public long transferTo(WritableByteChannel target, long position) throws IOException {
            long count = this.count - position;
            if (count < 0 || position < 0) {
                throw new IllegalArgumentException("position out of range: " + position + " (expected: 0 - " + (this.count - 1) + ")");
            }
            if (count == 0) {
                return 0L;
            }

            long bw = file.transferTo(this.position + position, count, target);
            byteWritten += bw;
            if (byteWritten == raf.length()) {
                releaseExternalResources();
            }
            return bw;
        }

        public void releaseExternalResources() {
            try {
                file.close();
            } catch (IOException e) {
                LOGGER.warn("Failed to close a file.", e);
            }

            try {
                raf.close();
            } catch (IOException e) {
                LOGGER.warn("Failed to close a file.", e);
            }
        }
    }

    private static class NettyTransferAdapter extends TransferCompletionHandler.TransferAdapter {

        private final ChannelBuffer content;
        private final FileInputStream file;
        private int byteRead = 0;

        public NettyTransferAdapter(FluentCaseInsensitiveStringsMap headers, ChannelBuffer content, File file) throws IOException {
            super(headers);
            this.content = content;
            if (file != null) {
                this.file = new FileInputStream(file);
            } else {
                this.file = null;
            }
        }

        @Override
        public void getBytes(byte[] bytes) {
            if (content.writableBytes() != 0) {
                content.getBytes(byteRead, bytes);
                byteRead += bytes.length;
            } else if (file != null) {
                try {
                    byteRead += file.read(bytes);
                } catch (IOException e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
        }
    }

    public AsyncHttpClientConfig getConfig() {
        return config;
    }

    private static final boolean validateWebSocketRequest(Request request, AsyncHandler<?> asyncHandler) {
        if (request.getMethod() != "GET" || !(asyncHandler instanceof WebSocketUpgradeHandler)) {
            return false;
        }
        return true;
    }

    private boolean exitAfterHandlingRedirect(Channel channel, NettyResponseFuture<?> future, Request request, HttpResponse response,
            int statusCode) throws Exception {

        if (AsyncHttpProviderUtils.followRedirect(config, request)
                && (statusCode == 302 || statusCode == 301 || statusCode == 303 || statusCode == 307)) {

            if (future.incrementAndGetCurrentRedirectCount() < config.getMaxRedirects()) {
                // allow 401 handling again
                future.getAndSetAuth(false);

                HttpHeaders responseHeaders = response.headers();

                String location = responseHeaders.get(HttpHeaders.Names.LOCATION);
                UriComponents uri = UriComponents.create(future.getURI(), location);
                if (!uri.equals(future.getURI())) {
                    final RequestBuilder nBuilder = new RequestBuilder(future.getRequest());

                    if (config.isRemoveQueryParamOnRedirect())
                        nBuilder.resetQuery();
                    else
                        nBuilder.addQueryParams(future.getRequest().getQueryParams());

                    if (!(statusCode < 302 || statusCode > 303) && !(statusCode == 302 && config.isStrict302Handling())) {
                        nBuilder.setMethod("GET");
                    }
                    final boolean initialConnectionKeepAlive = future.getKeepAlive();
                    final String initialPoolKey = getPoolKey(future);
                    future.setURI(uri);
                    UriComponents newURI = uri;
                    String targetScheme = request.getURI().getScheme();
                    if (targetScheme.equals(WEBSOCKET)) {
                        newURI = newURI.withNewScheme(WEBSOCKET);
                    }
                    if (targetScheme.equals(WEBSOCKET_SSL)) {
                        newURI = newURI.withNewScheme(WEBSOCKET_SSL);
                    }

                    LOGGER.debug("Redirecting to {}", newURI);
                    List<String> setCookieHeaders = responseHeaders.getAll(HttpHeaders.Names.SET_COOKIE2);
                    if (!isNonEmpty(setCookieHeaders)) {
                        setCookieHeaders = responseHeaders.getAll(HttpHeaders.Names.SET_COOKIE);
                    }

                    for (String cookieStr : setCookieHeaders) {
                        nBuilder.addOrReplaceCookie(CookieDecoder.decode(cookieStr));
                    }

                    AsyncCallable ac = newDrainCallable(future, channel, initialConnectionKeepAlive, initialPoolKey);

                    if (response.isChunked()) {
                        // We must make sure there is no bytes left before executing the next request.
                        Channels.setAttachment(channel, ac);
                    } else {
                        ac.call();
                    }
                    nextRequest(nBuilder.setURI(newURI).build(), future);
                    return true;
                }
            } else {
                throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
            }
        }
        return false;
    }

    private final AsyncCallable newDrainCallable(final NettyResponseFuture<?> future, final Channel channel, final boolean keepAlive,
            final String poolKey) {

        return new AsyncCallable(future) {
            public Object call() throws Exception {
                channelManager.tryToOfferChannelToPool(channel, keepAlive, poolKey);
                return null;
            }
        };
    }

    private final void configureKeepAlive(NettyResponseFuture<?> future, HttpResponse response) {
        String connectionHeader = response.headers().get(HttpHeaders.Names.CONNECTION);
        future.setKeepAlive(connectionHeader == null || connectionHeader.equalsIgnoreCase(HttpHeaders.Values.KEEP_ALIVE));
    }

    private final boolean exitAfterProcessingFilters(Channel channel, NettyResponseFuture<?> future, HttpResponse response,
            AsyncHandler handler, Request request, HttpResponseStatus status, HttpResponseHeaders responseHeaders) throws IOException {
        if (!config.getResponseFilters().isEmpty()) {
            FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(request).responseStatus(status)
                    .responseHeaders(responseHeaders).build();

            for (ResponseFilter asyncFilter : config.getResponseFilters()) {
                try {
                    fc = asyncFilter.filter(fc);
                    if (fc == null) {
                        throw new NullPointerException("FilterContext is null");
                    }
                } catch (FilterException efe) {
                    abort(future, efe);
                }
            }

            // The handler may have been wrapped.
            future.setAsyncHandler(fc.getAsyncHandler());

            // The request has changed
            if (fc.replayRequest()) {
                replayRequest(future, fc, channel);
                return true;
            }
        }
        return false;
    }

    private final boolean exitAfterHandling401(//
            final Channel channel,//
            final NettyResponseFuture<?> future,//
            HttpResponse response,//
            Request request,//
            int statusCode,//
            Realm realm,//
            ProxyServer proxyServer,//
            final RequestBuilder requestBuilder) throws Exception {

        if (statusCode == 401 && realm != null && !future.getAndSetAuth(true)) {

            List<String> wwwAuthHeaders = getNettyHeaderValuesByCaseInsensitiveName(response.headers(), HttpHeaders.Names.WWW_AUTHENTICATE);

            if (!wwwAuthHeaders.isEmpty()) {
                future.setState(NettyResponseFuture.STATE.NEW);
                Realm newRealm = null;

                FluentCaseInsensitiveStringsMap requestHeaders = request.getHeaders();

                if (!wwwAuthHeaders.contains("Kerberos") && (isNTLM(wwwAuthHeaders) || (wwwAuthHeaders.contains("Negotiate")))) {
                    // NTLM
                    newRealm = ntlmChallenge(wwwAuthHeaders, request, proxyServer, requestHeaders, realm, future, false);
                } else if (wwwAuthHeaders.contains("Negotiate")) {
                    // SPNEGO KERBEROS
                    newRealm = kerberosChallenge(wwwAuthHeaders, request, proxyServer, requestHeaders, realm, future, false);
                    if (newRealm == null)
                        return true;
                } else {
                    newRealm = new Realm.RealmBuilder().clone(realm) //
                            .setScheme(realm.getAuthScheme()) //
                            .setUri(request.getURI()) //
                            .setMethodName(request.getMethod()) //
                            .setUsePreemptiveAuth(true) //
                            .parseWWWAuthenticateHeader(wwwAuthHeaders.get(0))//
                            .build();
                }

                final Realm nr = newRealm;

                LOGGER.debug("Sending authentication to {}", request.getURI());
                final Request nextRequest = requestBuilder.setHeaders(requestHeaders).setRealm(nr).build();
                AsyncCallable ac = new AsyncCallable(future) {
                    public Object call() throws Exception {
                        // not waiting for the channel to be drained, so we might ended up pooling the initial channel and creating a new one
                        drainChannel(channel, future);
                        nextRequest(nextRequest, future);
                        return null;
                    }
                };

                if (future.getKeepAlive() && response.isChunked())
                    // we must make sure there is no chunk left before executing the next request
                    Channels.setAttachment(channel, ac);
                else
                    // FIXME couldn't we reuse the channel right now?
                    ac.call();
                return true;
            }
        }
        return false;
    }

    private final boolean exitAfterHandling407(//
            NettyResponseFuture<?> future,//
            HttpResponse response,//
            Request request,//
            int statusCode,//
            Realm realm,//
            ProxyServer proxyServer,//
            final RequestBuilder requestBuilder) throws Exception {

        if (statusCode == 407 && realm != null && !future.getAndSetAuth(true)) {
            List<String> proxyAuth = getNettyHeaderValuesByCaseInsensitiveName(response.headers(), HttpHeaders.Names.PROXY_AUTHENTICATE);
            if (!proxyAuth.isEmpty()) {
                LOGGER.debug("Sending proxy authentication to {}", request.getURI());

                future.setState(NettyResponseFuture.STATE.NEW);
                Realm newRealm = null;
                FluentCaseInsensitiveStringsMap requestHeaders = request.getHeaders();

                if (!proxyAuth.contains("Kerberos") && (isNTLM(proxyAuth) || (proxyAuth.contains("Negotiate")))) {
                    newRealm = ntlmProxyChallenge(proxyAuth, request, proxyServer, requestHeaders, realm, future);
                    // SPNEGO KERBEROS
                } else if (proxyAuth.contains("Negotiate")) {
                    newRealm = kerberosChallenge(proxyAuth, request, proxyServer, requestHeaders, realm, future, true);
                    if (newRealm == null)
                        return true;
                } else {
                    newRealm = new Realm.RealmBuilder().clone(realm)//
                            .setScheme(realm.getAuthScheme())//
                            .setUri(request.getURI())//
                            .setMethodName("CONNECT")//
                            .setTargetProxy(true)//
                            .setUsePreemptiveAuth(true)//
                            .parseProxyAuthenticateHeader(proxyAuth.get(0))//
                            .build();
                }

                Request req = requestBuilder.setHeaders(requestHeaders).setRealm(newRealm).build();
                future.setReuseChannel(true);
                future.setConnectAllowed(true);
                nextRequest(req, future);
                return true;
            }
        }
        return false;
    }

    private boolean exitAfterHandling100(Channel channel, NettyResponseFuture<?> future, int statusCode) {
        if (statusCode == 100) {
            future.getAndSetWriteHeaders(false);
            future.getAndSetWriteBody(true);
            writeRequest(channel, config, future);
            return true;
        }
        return false;
    }

    private boolean exitAfterHandlingConnect(Channel channel,//
            NettyResponseFuture<?> future,//
            Request request,//
            ProxyServer proxyServer,//
            int statusCode,//
            RequestBuilder requestBuilder,//
            HttpRequest nettyRequest) throws IOException {

        if (nettyRequest.getMethod().equals(HttpMethod.CONNECT) && statusCode == 200) {

            LOGGER.debug("Connected to {}:{}", proxyServer.getHost(), proxyServer.getPort());

            if (future.getKeepAlive()) {
                future.attachChannel(channel, true);
            }

            try {
                UriComponents requestURI = request.getURI();
                String scheme = requestURI.getScheme();
                String host = requestURI.getHost();
                int port = AsyncHttpProviderUtils.getDefaultPort(requestURI);

                LOGGER.debug("Connecting to proxy {} for scheme {}", proxyServer, scheme);
                upgradeProtocol(channel.getPipeline(), scheme, host, port);

            } catch (Throwable ex) {
                abort(future, ex);
            }
            Request req = requestBuilder.build();
            future.setReuseChannel(true);
            future.setConnectAllowed(false);
            nextRequest(req, future);
            return true;
        }
        return false;
    }

    private final boolean exitAfterHandlingStatus(Channel channel, NettyResponseFuture<?> future, HttpResponse response,
            AsyncHandler<?> handler, HttpResponseStatus status) throws IOException, Exception {
        if (!future.getAndSetStatusReceived(true) && updateStatusAndInterrupt(handler, status)) {
            finishUpdate(future, channel, response.isChunked());
            return true;
        }
        return false;
    }

    private final boolean exitAfterHandlingHeaders(Channel channel, NettyResponseFuture<?> future, HttpResponse response,
            AsyncHandler<?> handler, HttpResponseHeaders responseHeaders) throws IOException, Exception {
        if (!response.headers().isEmpty() && updateHeadersAndInterrupt(handler, responseHeaders)) {
            finishUpdate(future, channel, response.isChunked());
            return true;
        }
        return false;
    }

    private final boolean exitAfterHandlingBody(Channel channel, NettyResponseFuture<?> future, HttpResponse response,
            AsyncHandler<?> handler) throws Exception {
        if (!response.isChunked()) {
            updateBodyAndInterrupt(future, handler, new ResponseBodyPart(future.getURI(), response, NettyAsyncHttpProvider.this, true));
            finishUpdate(future, channel, false);
            return true;
        }
        return false;
    }

    private final boolean exitAfterHandlingHead(Channel channel, NettyResponseFuture<?> future, HttpResponse response,
            AsyncHandler<?> handler, HttpRequest nettyRequest) throws Exception {
        if (nettyRequest.getMethod().equals(HttpMethod.HEAD)) {
            updateBodyAndInterrupt(future, handler, new ResponseBodyPart(future.getURI(), response, NettyAsyncHttpProvider.this, true));
            markAsDone(future, channel);
            drainChannel(channel, future);
        }
        return false;
    }

    private final void handleHttpResponse(final HttpResponse response, final Channel channel,
            final NettyResponseFuture<?> future, AsyncHandler<?> handler) throws Exception {

        HttpRequest nettyRequest = future.getNettyRequest();
        Request request = future.getRequest();
        ProxyServer proxyServer = future.getProxyServer();
        LOGGER.debug("\n\nRequest {}\n\nResponse {}\n", nettyRequest, response);

        // Required if there is some trailing headers.
        future.setHttpResponse(response);

        configureKeepAlive(future, response);

        HttpResponseStatus status = new ResponseStatus(future.getURI(), response, NettyAsyncHttpProvider.this);
        HttpResponseHeaders responseHeaders = new ResponseHeaders(future.getURI(), response, NettyAsyncHttpProvider.this);

        if (exitAfterProcessingFilters(channel, future, response, handler, request, status, responseHeaders))
            return;

        final RequestBuilder requestBuilder = new RequestBuilder(future.getRequest());

        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

        int statusCode = response.getStatus().getCode();

        // FIXME
        if (exitAfterHandling401(channel, future, response, request, statusCode, realm, proxyServer, requestBuilder) || //
                exitAfterHandling407(future, response, request, statusCode, realm, proxyServer, requestBuilder) || //
                exitAfterHandling100(channel, future, statusCode) || //
                exitAfterHandlingRedirect(channel, future, request, response, statusCode) || //
                exitAfterHandlingConnect(channel, future, request, proxyServer, statusCode, requestBuilder, nettyRequest) || //
                exitAfterHandlingStatus(channel, future, response, handler, status) || //
                exitAfterHandlingHeaders(channel, future, response, handler, responseHeaders) || //
                exitAfterHandlingBody(channel, future, response, handler) || //
                exitAfterHandlingHead(channel, future, response, handler, nettyRequest)) {
            return;
        }
    }

    private final void handleChunk(final HttpChunk chunk, final Channel channel, final NettyResponseFuture<?> future,
            final AsyncHandler<?> handler) throws Exception {
        boolean last = chunk.isLast();
        // we don't notify updateBodyAndInterrupt with the last chunk as it's empty
        if (last || updateBodyAndInterrupt(future, handler, new ResponseBodyPart(future.getURI(), null, this, chunk, last))) {

            if (chunk instanceof HttpChunkTrailer) {
                HttpChunkTrailer chunkTrailer = (HttpChunkTrailer) chunk;
                if (!chunkTrailer.trailingHeaders().isEmpty()) {
                    ResponseHeaders responseHeaders = new ResponseHeaders(future.getURI(), future.getHttpResponse(), this, chunkTrailer);
                    updateHeadersAndInterrupt(handler, responseHeaders);
                }
            }
            finishUpdate(future, channel, !chunk.isLast());
        }
    }

    private final class HttpProtocol implements Protocol {

        public void handle(final Channel channel, final MessageEvent e, final NettyResponseFuture<?> future) throws Exception {

            // The connect timeout occurred.
            if (future.isDone()) {
                channelManager.closeChannel(channel);
                return;
            }

            future.touch();

            AsyncHandler<?> handler = future.getAsyncHandler();
            Object message = e.getMessage();
            try {
                if (message instanceof HttpResponse)
                    handleHttpResponse((HttpResponse) message, channel, future, handler);

                else if (message instanceof HttpChunk)
                    handleChunk((HttpChunk) message, channel, future, handler);

            } catch (Exception t) {
                if (t instanceof IOException && !config.getIOExceptionFilters().isEmpty()) {
                    FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(future.getRequest())
                            .ioException(IOException.class.cast(t)).build();
                    fc = handleIoException(fc, future);

                    if (fc.replayRequest()) {
                        replayRequest(future, fc, channel);
                        return;
                    }
                }

                try {
                    abort(future, t);
                } finally {
                    finishUpdate(future, channel, false);
                    throw t;
                }
            }
        }

        public void onError(Channel channel, ExceptionEvent e) {
        }

        public void onClose(Channel channel, ChannelStateEvent e) {
        }
    }

    private final class WebSocketProtocol implements Protocol {
        private static final byte OPCODE_CONT = 0x0;
        private static final byte OPCODE_TEXT = 0x1;
        private static final byte OPCODE_BINARY = 0x2;
        private static final byte OPCODE_UNKNOWN = -1;

        // We don't need to synchronize as replacing the "ws-decoder" will process using the same thread.
        private void invokeOnSucces(Channel channel, WebSocketUpgradeHandler h) {
            if (!h.touchSuccess()) {
                try {
                    h.onSuccess(new NettyWebSocket(channel));
                } catch (Exception ex) {
                    NettyAsyncHttpProvider.this.LOGGER.warn("onSuccess unexexpected exception", ex);
                }
            }
        }

        // @Override
        public void handle(Channel channel, MessageEvent e, final NettyResponseFuture future) throws Exception {

            WebSocketUpgradeHandler wsUpgradeHandler = (WebSocketUpgradeHandler) future.getAsyncHandler();
            Request request = future.getRequest();

            if (e.getMessage() instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) e.getMessage();
                HttpHeaders nettyResponseHeaders = response.headers();

                HttpResponseStatus s = new ResponseStatus(future.getURI(), response, NettyAsyncHttpProvider.this);
                HttpResponseHeaders responseHeaders = new ResponseHeaders(future.getURI(), response, NettyAsyncHttpProvider.this);
                FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(wsUpgradeHandler).request(request)
                        .responseStatus(s).responseHeaders(responseHeaders).build();
                for (ResponseFilter asyncFilter : config.getResponseFilters()) {
                    try {
                        fc = asyncFilter.filter(fc);
                        if (fc == null) {
                            throw new NullPointerException("FilterContext is null");
                        }
                    } catch (FilterException efe) {
                        abort(future, efe);
                    }
                }

                // The handler may have been wrapped.
                future.setAsyncHandler(fc.getAsyncHandler());

                // The request has changed
                if (fc.replayRequest()) {
                    replayRequest(future, fc, channel);
                    return;
                }

                future.setHttpResponse(response);
                if (exitAfterHandlingRedirect(channel, future, request, response, response.getStatus().getCode()))
                    return;

                final org.jboss.netty.handler.codec.http.HttpResponseStatus status = new org.jboss.netty.handler.codec.http.HttpResponseStatus(
                        101, "Web Socket Protocol Handshake");

                final boolean validStatus = response.getStatus().equals(status);
                final boolean validUpgrade = nettyResponseHeaders.contains(HttpHeaders.Names.UPGRADE);
                String c = nettyResponseHeaders.get(HttpHeaders.Names.CONNECTION);
                if (c == null) {
                    c = nettyResponseHeaders.get("connection");
                }

                final boolean validConnection = c == null ? false : c.equalsIgnoreCase(HttpHeaders.Values.UPGRADE);

                s = new ResponseStatus(future.getURI(), response, NettyAsyncHttpProvider.this);
                final boolean statusReceived = wsUpgradeHandler.onStatusReceived(s) == STATE.UPGRADE;

                if (!statusReceived) {
                    try {
                        wsUpgradeHandler.onCompleted();
                    } finally {
                        future.done();
                    }
                    return;
                }

                final boolean headerOK = wsUpgradeHandler.onHeadersReceived(responseHeaders) == STATE.CONTINUE;
                if (!headerOK || !validStatus || !validUpgrade || !validConnection) {
                    abort(future, new IOException("Invalid handshake response"));
                    return;
                }

                String accept = nettyResponseHeaders.get(HttpHeaders.Names.SEC_WEBSOCKET_ACCEPT);
                String key = WebSocketUtil.getAcceptKey(future.getNettyRequest().headers().get(HttpHeaders.Names.SEC_WEBSOCKET_KEY));
                if (accept == null || !accept.equals(key)) {
                    abort(future, new IOException(String.format("Invalid challenge. Actual: %s. Expected: %s", accept, key)));
                    return;
                }

                channel.getPipeline().replace(HTTP_HANDLER, "ws-encoder", new WebSocket08FrameEncoder(true));
                channel.getPipeline().addBefore(WS_PROCESSOR, "ws-decoder", new WebSocket08FrameDecoder(false, false));

                invokeOnSucces(channel, wsUpgradeHandler);
                future.done();
            } else if (e.getMessage() instanceof WebSocketFrame) {

                invokeOnSucces(channel, wsUpgradeHandler);

                final WebSocketFrame frame = (WebSocketFrame) e.getMessage();

                byte pendingOpcode = OPCODE_UNKNOWN;
                if (frame instanceof TextWebSocketFrame) {
                    pendingOpcode = OPCODE_TEXT;
                } else if (frame instanceof BinaryWebSocketFrame) {
                    pendingOpcode = OPCODE_BINARY;
                }

                HttpChunk webSocketChunk = new HttpChunk() {
                    private ChannelBuffer content;

                    // @Override
                    public boolean isLast() {
                        return false;
                    }

                    // @Override
                    public ChannelBuffer getContent() {
                        return content;
                    }

                    // @Override
                    public void setContent(ChannelBuffer content) {
                        this.content = content;
                    }
                };

                if (frame.getBinaryData() != null) {
                    webSocketChunk.setContent(ChannelBuffers.wrappedBuffer(frame.getBinaryData()));
                    ResponseBodyPart rp = new ResponseBodyPart(future.getURI(), null, NettyAsyncHttpProvider.this, webSocketChunk, true);
                    wsUpgradeHandler.onBodyPartReceived(rp);

                    NettyWebSocket webSocket = NettyWebSocket.class.cast(wsUpgradeHandler.onCompleted());

                    if (webSocket != null) {
                        if (pendingOpcode == OPCODE_BINARY) {
                            webSocket.onBinaryFragment(rp.getBodyPartBytes(), frame.isFinalFragment());
                        } else if (pendingOpcode == OPCODE_TEXT) {
                            webSocket.onTextFragment(frame.getBinaryData().toString(UTF8), frame.isFinalFragment());
                        }

                        if (frame instanceof CloseWebSocketFrame) {
                            try {
                                Channels.setDiscard(channel);
                                webSocket.onClose(CloseWebSocketFrame.class.cast(frame).getStatusCode(),
                                        CloseWebSocketFrame.class.cast(frame).getReasonText());
                            } finally {
                                wsUpgradeHandler.resetSuccess();
                            }
                        }
                    } else {
                        LOGGER.debug("UpgradeHandler returned a null NettyWebSocket ");
                    }
                }
            } else {
                LOGGER.error("Invalid message {}", e.getMessage());
            }
        }

        // @Override
        public void onError(Channel channel, ExceptionEvent e) {
            try {
                Object attachment = Channels.getAttachment(channel);
                LOGGER.warn("onError {}", e);
                if (!(attachment instanceof NettyResponseFuture)) {
                    return;
                }

                NettyResponseFuture<?> nettyResponse = (NettyResponseFuture<?>) attachment;
                WebSocketUpgradeHandler h = WebSocketUpgradeHandler.class.cast(nettyResponse.getAsyncHandler());

                NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());
                if (webSocket != null) {
                    webSocket.onError(e.getCause());
                    webSocket.close();
                }
            } catch (Throwable t) {
                LOGGER.error("onError", t);
            }
        }

        // @Override
        public void onClose(Channel channel, ChannelStateEvent e) {
            LOGGER.trace("onClose {}", e);

            Object attachment = Channels.getAttachment(channel);
            if (attachment instanceof NettyResponseFuture) {
                try {
                    NettyResponseFuture<?> nettyResponse = (NettyResponseFuture<?>) attachment;
                    WebSocketUpgradeHandler h = WebSocketUpgradeHandler.class.cast(nettyResponse.getAsyncHandler());
                    h.resetSuccess();

                } catch (Throwable t) {
                    LOGGER.error("onError", t);
                }
            }
        }
    }

    public boolean isClose() {
        return isClose.get();
    }

    public Timeout newTimeoutInMs(TimerTask task, long delayInMs) {
        return nettyTimer.newTimeout(task, delayInMs, TimeUnit.MILLISECONDS);
    }

    private static boolean isWebSocket(String scheme) {
        return WEBSOCKET.equalsIgnoreCase(scheme) || WEBSOCKET_SSL.equalsIgnoreCase(scheme);
    }

    private static boolean isSecure(String scheme) {
        return HTTPS.equalsIgnoreCase(scheme) || WEBSOCKET_SSL.equalsIgnoreCase(scheme);
    }

    private static boolean isSecure(UriComponents uri) {
        return isSecure(uri.getScheme());
    }
}
