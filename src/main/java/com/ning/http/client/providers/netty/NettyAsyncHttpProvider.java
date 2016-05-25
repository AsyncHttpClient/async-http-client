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

import static com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig.BOSS_EXECUTOR_SERVICE;
import static com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig.DISABLE_NESTED_REQUEST;
import static com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig.EXECUTE_ASYNC_CONNECT;
import static com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig.HTTPS_CLIENT_CODEC_MAX_CHUNK_SIZE;
import static com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig.HTTPS_CLIENT_CODEC_MAX_HEADER_SIZE;
import static com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig.HTTPS_CLIENT_CODEC_MAX_INITIAL_LINE_LENGTH;
import static com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig.HTTP_CLIENT_CODEC_MAX_CHUNK_SIZE;
import static com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig.HTTP_CLIENT_CODEC_MAX_HEADER_SIZE;
import static com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig.HTTP_CLIENT_CODEC_MAX_INITIAL_LINE_LENGTH;
import static com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig.REUSE_ADDRESS;
import static com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig.SOCKET_CHANNEL_FACTORY;
import static com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig.USE_BLOCKING_IO;
import static com.ning.http.util.AsyncHttpProviderUtils.DEFAULT_CHARSET;
import static com.ning.http.util.MiscUtil.isNonEmpty;
import static org.jboss.netty.channel.Channels.pipeline;
import static org.jboss.netty.handler.ssl.SslHandler.getDefaultBufferPool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLEngine;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferOutputStream;
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
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.oio.OioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.PrematureChannelClosureException;
import org.jboss.netty.handler.codec.http.DefaultHttpChunkTrailer;
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
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket08FrameDecoder;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket08FrameEncoder;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.handler.ssl.ImmediateExecutor;
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
import com.ning.http.client.ConnectionsPool;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.PerRequestConfig;
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
import com.ning.http.client.providers.netty.spnego.SpnegoEngine;
import com.ning.http.client.providers.netty.timeout.IdleConnectionTimeoutTimerTask;
import com.ning.http.client.providers.netty.timeout.RequestTimeoutTimerTask;
import com.ning.http.client.providers.netty.timeout.TimeoutsHolder;
import com.ning.http.client.websocket.WebSocketUpgradeHandler;
import com.ning.http.multipart.MultipartBody;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.AuthenticatorUtils;
import com.ning.http.util.CleanupChannelGroup;
import com.ning.http.util.MiscUtil;
import com.ning.http.util.ProxyUtils;
import com.ning.http.util.SslUtils;
import com.ning.http.util.UTF8UrlEncoder;

public class NettyAsyncHttpProvider extends SimpleChannelUpstreamHandler implements AsyncHttpProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyAsyncHttpProvider.class);

    public static final String GZIP_DEFLATE = HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE;

    public static final IOException REMOTELY_CLOSED_EXCEPTION = new IOException("Remotely Closed");
    static {
        REMOTELY_CLOSED_EXCEPTION.setStackTrace(new StackTraceElement[0]);
    }
    public final static String HTTP_HANDLER = "httpHandler";
    public final static String SSL_HANDLER = "sslHandler";
    public final static String HTTP_PROCESSOR = "httpProcessor";
    public final static String WS_PROCESSOR = "wsProcessor";

    private final static String HTTPS = "https";
    private final static String HTTP = "http";
    private static final String WEBSOCKET = "ws";
    private static final String WEBSOCKET_SSL = "wss";
    private final static Logger log = LoggerFactory.getLogger(NettyAsyncHttpProvider.class);
    private final static Charset UTF8 = Charset.forName("UTF-8");
    private final ClientBootstrap plainBootstrap;
    private final ClientBootstrap secureBootstrap;
    private final ClientBootstrap webSocketBootstrap;
    private final ClientBootstrap secureWebSocketBootstrap;
    private final static int MAX_BUFFERED_BYTES = 8192;
    private final AsyncHttpClientConfig config;
    private final AtomicBoolean isClose = new AtomicBoolean(false);
    private final ClientSocketChannelFactory socketChannelFactory;
    private final boolean allowReleaseSocketChannelFactory;
    private int httpClientCodecMaxInitialLineLength = 4096;
    private int httpClientCodecMaxHeaderSize = 8192;
    private int httpClientCodecMaxChunkSize = 8192;
    private int httpsClientCodecMaxInitialLineLength = 4096;
    private int httpsClientCodecMaxHeaderSize = 8192;
    private int httpsClientCodecMaxChunkSize = 8192;

    private final ChannelGroup openChannels = new CleanupChannelGroup("asyncHttpClient") {
        @Override
        public boolean remove(Object o) {
            boolean removed = super.remove(o);
            if (removed && trackConnections) {
                freeConnections.release();
            }
            return removed;
        }
    };
    private final ConnectionsPool<String, Channel> connectionsPool;
    private Semaphore freeConnections = null;
    private final NettyAsyncHttpProviderConfig providerConfig;
    private boolean executeConnectAsync = true;
    public static final ThreadLocal<Boolean> IN_IO_THREAD = new ThreadLocalBoolean();
    private final boolean trackConnections;
    private final boolean useRawUrl;
    private final boolean disableZeroCopy;
    private final static NTLMEngine ntlmEngine = new NTLMEngine();
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

        if (providerConfig.getProperty(USE_BLOCKING_IO) != null) {
            socketChannelFactory = new OioClientSocketChannelFactory(config.executorService());
            allowReleaseSocketChannelFactory = true;
        } else {
            // check if external NioClientSocketChannelFactory is defined
            Object oo = providerConfig.getProperty(SOCKET_CHANNEL_FACTORY);
            if (oo instanceof NioClientSocketChannelFactory) {
                socketChannelFactory = NioClientSocketChannelFactory.class.cast(oo);

                // cannot allow releasing shared channel factory
                allowReleaseSocketChannelFactory = false;
            } else {
                ExecutorService e;
                Object o = providerConfig.getProperty(BOSS_EXECUTOR_SERVICE);
                if (o instanceof ExecutorService) {
                    e = ExecutorService.class.cast(o);
                } else {
                    e = Executors.newCachedThreadPool();
                }
                int numWorkers = config.getIoThreadMultiplier() * Runtime.getRuntime().availableProcessors();
                log.trace("Number of application's worker threads is {}", numWorkers);
                socketChannelFactory = new NioClientSocketChannelFactory(e, config.executorService(), numWorkers);
                allowReleaseSocketChannelFactory = true;
            }
        }

        allowStopNettyTimer = providerConfig.getNettyTimer() == null;
        nettyTimer = allowStopNettyTimer ? newNettyTimer() : providerConfig.getNettyTimer();

        handshakeTimeoutInMillis = providerConfig.getHandshakeTimeoutInMillis();

        plainBootstrap = new ClientBootstrap(socketChannelFactory);
        secureBootstrap = new ClientBootstrap(socketChannelFactory);
        webSocketBootstrap = new ClientBootstrap(socketChannelFactory);
        secureWebSocketBootstrap = new ClientBootstrap(socketChannelFactory);
        this.config = config;

        configureNetty();

        // This is dangerous as we can't catch a wrong typed ConnectionsPool
        ConnectionsPool<String, Channel> cp = (ConnectionsPool<String, Channel>) config.getConnectionsPool();
        if (cp == null && config.getAllowPoolingConnection()) {
            cp = new NettyConnectionsPool(this, nettyTimer);
        } else if (cp == null) {
            cp = new NonConnectionsPool();
        }
        this.connectionsPool = cp;

        if (config.getMaxTotalConnections() != -1) {
            trackConnections = true;
            freeConnections = new Semaphore(config.getMaxTotalConnections());
        } else {
            trackConnections = false;
        }

        useRawUrl = config.isUseRawUrl();
        disableZeroCopy = providerConfig.isDisableZeroCopy();
    }

    private Timer newNettyTimer() {
        HashedWheelTimer timer = new HashedWheelTimer();
        timer.start();
        return timer;
    }
    
    @Override
    public String toString() {
        int availablePermits = freeConnections != null ? freeConnections.availablePermits() : 0;
        return String.format("NettyAsyncHttpProvider:\n\t- maxConnections: %d\n\t- openChannels: %s\n\t- connectionPools: %s",//
                config.getMaxTotalConnections() - availablePermits,//
                openChannels.toString(),//
                connectionsPool.toString());
    }

    void configureNetty() {
        if (providerConfig != null) {
            for (Entry<String, Object> entry : providerConfig.propertiesSet()) {
                plainBootstrap.setOption(entry.getKey(), entry.getValue());
            }
            configureHttpClientCodec();
            configureHttpsClientCodec();
        }

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
        DefaultChannelFuture.setUseDeadLockChecker(false);

        if (providerConfig != null) {
            Object value = providerConfig.getProperty(EXECUTE_ASYNC_CONNECT);
            if (value instanceof Boolean) {
                executeConnectAsync = Boolean.class.cast(value);
            } else if (providerConfig.getProperty(DISABLE_NESTED_REQUEST) != null) {
                DefaultChannelFuture.setUseDeadLockChecker(true);
            }
        }

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

    protected void configureHttpClientCodec() {
        httpClientCodecMaxInitialLineLength = providerConfig.getProperty(HTTP_CLIENT_CODEC_MAX_INITIAL_LINE_LENGTH, Integer.class, httpClientCodecMaxInitialLineLength);
        httpClientCodecMaxHeaderSize = providerConfig.getProperty(HTTP_CLIENT_CODEC_MAX_HEADER_SIZE, Integer.class, httpClientCodecMaxHeaderSize);
        httpClientCodecMaxChunkSize = providerConfig.getProperty(HTTP_CLIENT_CODEC_MAX_CHUNK_SIZE, Integer.class, httpClientCodecMaxChunkSize);
    }

    protected void configureHttpsClientCodec() {
        httpsClientCodecMaxInitialLineLength = providerConfig.getProperty(HTTPS_CLIENT_CODEC_MAX_INITIAL_LINE_LENGTH, Integer.class, httpsClientCodecMaxInitialLineLength);
        httpsClientCodecMaxHeaderSize = providerConfig.getProperty(HTTPS_CLIENT_CODEC_MAX_HEADER_SIZE, Integer.class, httpsClientCodecMaxHeaderSize);
        httpsClientCodecMaxChunkSize = providerConfig.getProperty(HTTPS_CLIENT_CODEC_MAX_CHUNK_SIZE, Integer.class, httpsClientCodecMaxChunkSize);
    }

    void constructSSLPipeline(final NettyConnectListener<?> cl) {

        secureBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            /* @Override */
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();

                try {
                    SSLEngine sslEngine = createSSLEngine();
                    SslHandler sslHandler = handshakeTimeoutInMillis > 0 ? new SslHandler(sslEngine, getDefaultBufferPool(), false, ImmediateExecutor.INSTANCE, nettyTimer,
                            handshakeTimeoutInMillis) : new SslHandler(sslEngine);
                    pipeline.addLast(SSL_HANDLER, sslHandler);
                } catch (Throwable ex) {
                    abort(cl.future(), ex);
                }

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

                try {
                    pipeline.addLast(SSL_HANDLER, new SslHandler(createSSLEngine()));
                } catch (Throwable ex) {
                    abort(cl.future(), ex);
                }

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

    private Channel lookupInCache(URI uri, ProxyServer proxy, ConnectionPoolKeyStrategy strategy) {
        final Channel channel = connectionsPool.poll(getPoolKey(uri, proxy, strategy));

        if (channel != null) {
            log.debug("Using cached Channel {}\n for uri {}\n", channel, uri);

            try {
                // Always make sure the channel who got cached support the proper protocol. It could
                // only occurs when a HttpMethod.CONNECT is used against a proxy that requires upgrading from http to
                // https.
                return verifyChannelPipeline(channel, uri.getScheme());
            } catch (Exception ex) {
                log.debug(ex.getMessage(), ex);
            }
        }
        return null;
    }

    private SSLEngine createSSLEngine() throws IOException, GeneralSecurityException {
        SSLEngine sslEngine = config.getSSLEngineFactory().newSSLEngine();
        if (sslEngine == null) {
            sslEngine = SslUtils.getSSLEngine();
        }
        return sslEngine;
    }

    private HttpClientCodec createHttpClientCodec() {
        return new HttpClientCodec(httpClientCodecMaxInitialLineLength, httpClientCodecMaxHeaderSize, httpClientCodecMaxChunkSize);
    }

    private HttpClientCodec createHttpsClientCodec() {
        return new HttpClientCodec(httpsClientCodecMaxInitialLineLength, httpsClientCodecMaxHeaderSize, httpsClientCodecMaxChunkSize);
    }

    private Channel verifyChannelPipeline(Channel channel, String scheme) throws IOException, GeneralSecurityException {

        if (channel.getPipeline().get(SSL_HANDLER) != null && HTTP.equalsIgnoreCase(scheme)) {
            channel.getPipeline().remove(SSL_HANDLER);
        } else if (channel.getPipeline().get(HTTP_HANDLER) != null && HTTP.equalsIgnoreCase(scheme)) {
            return channel;
        } else if (channel.getPipeline().get(SSL_HANDLER) == null && isSecure(scheme)) {
            channel.getPipeline().addFirst(SSL_HANDLER, new SslHandler(createSSLEngine()));
        }
        return channel;
    }

    protected final <T> void writeRequest(final Channel channel, final AsyncHttpClientConfig config, final NettyResponseFuture<T> future) {

        HttpRequest nettyRequest = future.getNettyRequest();
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
                        nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, length);
                    } else {
                        nettyRequest.setHeader(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
                    }

                } else if (future.getRequest().getParts() != null) {
                    String contentType = nettyRequest.getHeader(HttpHeaders.Names.CONTENT_TYPE);
                    String contentLength = nettyRequest.getHeader(HttpHeaders.Names.CONTENT_LENGTH);

                    long length = -1;
                    if (contentLength != null) {
                        length = Long.parseLong(contentLength);
                    } else {
                        nettyRequest.addHeader(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
                    }

                    body = new MultipartBody(future.getRequest().getParts(), contentType, length);
                }
            }

            if (future.getAsyncHandler() instanceof TransferCompletionHandler) {

                FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
                for (String s : nettyRequest.getHeaderNames()) {
                    for (String header : nettyRequest.getHeaders(s)) {
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
                    log.debug(cause.getMessage(), cause);
                    try {
                        channel.close();
                    } catch (RuntimeException ex) {
                        log.debug(ex.getMessage(), ex);
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
                                writeFuture = channel.write(new ChunkedFile(raf, 0, raf.length(), MAX_BUFFERED_BYTES));
                            } else {
                                final FileRegion region = new OptimizedFileRegion(raf, 0, raf.length());
                                writeFuture = channel.write(region);
                            }
                            writeFuture.addListener(new ProgressListener(false, future.getAsyncHandler(), future) {
                                public void operationComplete(ChannelFuture cf) {
                                    try {
                                        raf.close();
                                    } catch (IOException e) {
                                        log.warn("Failed to close request body: {}", e.getMessage(), e);
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
                                    log.warn("Failed to close request body: {}", e.getMessage(), e);
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
                log.debug(ex.getMessage(), ex);
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
                Timeout idleConnectionTimeout = newTimeoutInMs(new IdleConnectionTimeoutTimerTask(future, this, timeoutsHolder, requestTimeoutInMs, idleConnectionTimeoutInMs),
                        idleConnectionTimeoutInMs);
                timeoutsHolder.idleConnectionTimeout = idleConnectionTimeout;
            }
            future.setTimeoutsHolder(timeoutsHolder);

        } catch (RejectedExecutionException ex) {
            abort(future, ex);
        }
    }

    protected final static HttpRequest buildRequest(AsyncHttpClientConfig config, Request request, URI uri, boolean allowConnect, ChannelBuffer buffer, ProxyServer proxyServer)
            throws IOException {

        String method = request.getMethod();
        if (allowConnect && proxyServer != null && useProxyConnect(uri)) {
            method = HttpMethod.CONNECT.toString();
        }
        return construct(config, request, new HttpMethod(method), uri, buffer, proxyServer);
    }

    protected final static boolean useProxyConnect(URI uri) {
        return isSecure(uri) || isWebSocket(uri.getScheme());
    }

    private static SpnegoEngine getSpnegoEngine() {
        if (spnegoEngine == null)
            spnegoEngine = new SpnegoEngine();
        return spnegoEngine;
    }

    private static HttpRequest construct(AsyncHttpClientConfig config, Request request, HttpMethod m, URI uri, ChannelBuffer buffer, ProxyServer proxyServer) throws IOException {

        String host = null;

        if (request.getVirtualHost() != null) {
            host = request.getVirtualHost();
        } else {
            host = AsyncHttpProviderUtils.getHost(uri);
        }

        HttpRequest nettyRequest;
        if (m.equals(HttpMethod.CONNECT)) {
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_0, m, AsyncHttpProviderUtils.getAuthority(uri));
        } else {
            String path = null;
            if (proxyServer != null && !(useProxyConnect(uri) && config.isUseRelativeURIsWithConnectProxies()))
                path = uri.toString();
            else if (uri.getRawQuery() != null)
                path = uri.getRawPath() + "?" + uri.getRawQuery();
            else
                path = uri.getRawPath();
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, m, path);
        }
        boolean webSocket = isWebSocket(uri.getScheme());
        if (!m.equals(HttpMethod.CONNECT) && webSocket) {
            nettyRequest.addHeader(HttpHeaders.Names.UPGRADE, HttpHeaders.Values.WEBSOCKET);
            nettyRequest.addHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.UPGRADE);
            nettyRequest.addHeader(HttpHeaders.Names.ORIGIN, "http://" + uri.getHost() + ":" + uri.getPort());
            nettyRequest.addHeader(HttpHeaders.Names.SEC_WEBSOCKET_KEY, WebSocketUtil.getKey());
            nettyRequest.addHeader(HttpHeaders.Names.SEC_WEBSOCKET_VERSION, "13");
        }

        if (host != null) {
            if (request.getVirtualHost() != null || uri.getPort() == -1) {
                nettyRequest.setHeader(HttpHeaders.Names.HOST, host);
            } else {
                nettyRequest.setHeader(HttpHeaders.Names.HOST, host + ":" + uri.getPort());
            }
        } else {
            host = "127.0.0.1";
        }

        if (!m.equals(HttpMethod.CONNECT)) {
            for (Entry<String, List<String>> header : request.getHeaders()) {
                String name = header.getKey();
                if (!HttpHeaders.Names.HOST.equalsIgnoreCase(name)) {
                    for (String value : header.getValue()) {
                        nettyRequest.addHeader(name, value);
                    }
                }
            }

            if (config.isCompressionEnabled()) {
                nettyRequest.setHeader(HttpHeaders.Names.ACCEPT_ENCODING, GZIP_DEFLATE);
            }
        } else {
            List<String> auth = request.getHeaders().get(HttpHeaders.Names.PROXY_AUTHORIZATION);
            if (isNTLM(auth)) {
                nettyRequest.addHeader(HttpHeaders.Names.PROXY_AUTHORIZATION, auth.get(0));
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
                nettyRequest.addHeader(HttpHeaders.Names.AUTHORIZATION, AuthenticatorUtils.computeBasicAuthentication(realm));
                break;
            case DIGEST:
                if (isNonEmpty(realm.getNonce())) {
                    try {
                        nettyRequest.addHeader(HttpHeaders.Names.AUTHORIZATION, AuthenticatorUtils.computeDigestAuthentication(realm));
                    } catch (NoSuchAlgorithmException e) {
                        throw new SecurityException(e);
                    }
                }
                break;
            case NTLM:
                try {
                    nettyRequest.addHeader(HttpHeaders.Names.AUTHORIZATION, ntlmEngine.generateType1Msg("NTLM " + domain, authHost));
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
                nettyRequest.addHeader(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);
                break;
            case NONE:
                break;
            default:
                throw new IllegalStateException(String.format("Invalid Authentication %s", realm.toString()));
            }
        }

        if (!webSocket && !request.getHeaders().containsKey(HttpHeaders.Names.CONNECTION)) {
            nettyRequest.setHeader(HttpHeaders.Names.CONNECTION, AsyncHttpProviderUtils.keepAliveHeaderValue(config));
        }

        if (proxyServer != null) {
            if (!request.getHeaders().containsKey("Proxy-Connection")) {
                nettyRequest.setHeader("Proxy-Connection", AsyncHttpProviderUtils.keepAliveHeaderValue(config));
            }

            if (proxyServer.getPrincipal() != null) {
                if (isNonEmpty(proxyServer.getNtlmDomain())) {

                    List<String> auth = request.getHeaders().get(HttpHeaders.Names.PROXY_AUTHORIZATION);
                    if (!isNTLM(auth)) {
                        try {
                            String msg = ntlmEngine.generateType1Msg(proxyServer.getNtlmDomain(), proxyServer.getHost());
                            nettyRequest.setHeader(HttpHeaders.Names.PROXY_AUTHORIZATION, "NTLM " + msg);
                        } catch (NTLMEngineException e) {
                            IOException ie = new IOException();
                            ie.initCause(e);
                            throw ie;
                        }
                    }
                } else {
                    nettyRequest.setHeader(HttpHeaders.Names.PROXY_AUTHORIZATION, AuthenticatorUtils.computeBasicAuthentication(proxyServer));
                }
            }
        }

        // Add default accept headers.
        if (!request.getHeaders().containsKey(HttpHeaders.Names.ACCEPT)) {
            nettyRequest.setHeader(HttpHeaders.Names.ACCEPT, "*/*");
        }

        String userAgentHeader = request.getHeaders().getFirstValue(HttpHeaders.Names.USER_AGENT);
        if (userAgentHeader != null) {
            nettyRequest.setHeader(HttpHeaders.Names.USER_AGENT, userAgentHeader);
        } else if (config.getUserAgent() != null) {
            nettyRequest.setHeader(HttpHeaders.Names.USER_AGENT, config.getUserAgent());
        } else {
            nettyRequest.setHeader(HttpHeaders.Names.USER_AGENT, AsyncHttpProviderUtils.constructUserAgent(NettyAsyncHttpProvider.class));
        }

        if (!m.equals(HttpMethod.CONNECT)) {
            if (isNonEmpty(request.getCookies())) {
                nettyRequest.setHeader(HttpHeaders.Names.COOKIE, CookieEncoder.encode(request.getCookies()));
            }

            String bodyCharset = request.getBodyEncoding() == null ? DEFAULT_CHARSET : request.getBodyEncoding();

            // We already have processed the body.
            if (buffer != null && buffer.writerIndex() != 0) {
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, buffer.writerIndex());
                nettyRequest.setContent(buffer);
            } else if (request.getByteData() != null) {
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getByteData().length));
                nettyRequest.setContent(ChannelBuffers.wrappedBuffer(request.getByteData()));
            } else if (request.getStringData() != null) {
                byte[] bytes = request.getStringData().getBytes(bodyCharset);
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(bytes.length));
                nettyRequest.setContent(ChannelBuffers.wrappedBuffer(bytes));
            } else if (isNonEmpty(request.getParams())) {
                StringBuilder sb = new StringBuilder();
                for (final Entry<String, List<String>> paramEntry : request.getParams()) {
                    final String key = paramEntry.getKey();
                    for (final String value : paramEntry.getValue()) {
                        if (sb.length() > 0) {
                            sb.append("&");
                        }
                        UTF8UrlEncoder.appendEncoded(sb, key);
                        sb.append("=");
                        UTF8UrlEncoder.appendEncoded(sb, value);
                    }
                }
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(sb.length()));
                nettyRequest.setContent(ChannelBuffers.wrappedBuffer(sb.toString().getBytes(bodyCharset)));

                if (!request.getHeaders().containsKey(HttpHeaders.Names.CONTENT_TYPE)) {
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
                }

            } else if (request.getParts() != null) {
                MultipartRequestEntity mre = AsyncHttpProviderUtils.createMultipartRequestEntity(request.getParts(), request.getHeaders());

                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_TYPE, mre.getContentType());
                long contentLength = mre.getContentLength();
                if (contentLength >= 0) {
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(contentLength));
                }

            } else if (request.getEntityWriter() != null) {
                int length = (int) request.getContentLength();

                if (length == -1) {
                    length = MAX_BUFFERED_BYTES;
                }

                ChannelBuffer b = ChannelBuffers.dynamicBuffer(length);
                request.getEntityWriter().writeEntity(new ChannelBufferOutputStream(b));
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, b.writerIndex());
                nettyRequest.setContent(b);
            } else if (request.getFile() != null) {
                File file = request.getFile();
                if (!file.isFile()) {
                    throw new IOException(String.format("File %s is not a file or doesn't exist", file.getAbsolutePath()));
                }
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, file.length());
            }
        }
        return nettyRequest;
    }

    public void close() {
        if (isClose.compareAndSet(false, true)) {
            try {
                connectionsPool.destroy();
                openChannels.close();

                for (Channel channel : openChannels) {
                    ChannelHandlerContext ctx = channel.getPipeline().getContext(NettyAsyncHttpProvider.class);
                    if (ctx.getAttachment() instanceof NettyResponseFuture<?>) {
                        NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();
                        future.cancelTimeouts();
                    }
                }

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
                log.warn("Unexpected error on close", t);
            }
        }
    }

    /* @Override */

    public Response prepareResponse(final HttpResponseStatus status, final HttpResponseHeaders headers, final List<HttpResponseBodyPart> bodyParts) {
        return new NettyResponse(status, headers, bodyParts);
    }

    /* @Override */

    public <T> ListenableFuture<T> execute(Request request, final AsyncHandler<T> asyncHandler) throws IOException {
        return doConnect(request, asyncHandler, null, true, executeConnectAsync, false);
    }

    private <T> void execute(final Request request, final NettyResponseFuture<T> f, boolean useCache, boolean asyncConnect, boolean reclaimCache) throws IOException {
        doConnect(request, f.getAsyncHandler(), f, useCache, asyncConnect, reclaimCache);
    }

    private <T> NettyResponseFuture<T> buildNettyResponseFutureWithCachedChannel(Request request, AsyncHandler<T> asyncHandler, NettyResponseFuture<T> f, ProxyServer proxyServer,
            URI uri, ChannelBuffer bufferedBytes, int maxTry) throws IOException {

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
                    f.channel().getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(f);
                    return f;
                } else
                    // else, channel was closed by the server since we fetched it from the pool, starting over
                    f.attachChannel(null);
            }
        }
        return null;
    }

    private <T> ListenableFuture<T> doConnect(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> f, boolean useCache, boolean asyncConnect,
            boolean reclaimCache) throws IOException {

        if (isClose()) {
            throw new IOException("Closed");
        }

        URI uri = useRawUrl ? request.getRawURI() : request.getURI();
        
        if (uri.getScheme().startsWith(WEBSOCKET) && !validateWebSocketRequest(request, asyncHandler)) {
            throw new IOException("WebSocket method must be a GET");
        }

        ProxyServer proxyServer = ProxyUtils.getProxyServer(config, request);

        boolean resultOfAConnect = f != null && f.getNettyRequest() != null && f.getNettyRequest().getMethod().equals(HttpMethod.CONNECT);
        boolean useProxy = proxyServer != null && !resultOfAConnect;

        ChannelBuffer bufferedBytes = null;
        if (f != null && f.getRequest().getFile() == null && !f.getNettyRequest().getMethod().getName().equals(HttpMethod.CONNECT.getName())) {
            bufferedBytes = f.getNettyRequest().getContent();
        }

        boolean useSSl = isSecure(uri) && !useProxy;

        if (useCache) {
            // 3 tentatives
            NettyResponseFuture<T> connectedFuture = buildNettyResponseFutureWithCachedChannel(request, asyncHandler, f, proxyServer, uri, bufferedBytes, 3);

            if (connectedFuture != null) {
                log.debug("\nUsing cached Channel {}\n for request \n{}\n", connectedFuture.channel(), connectedFuture.getNettyRequest());

                try {
                    writeRequest(connectedFuture.channel(), config, connectedFuture);
                } catch (Exception ex) {
                    log.debug("writeRequest failure", ex);
                    if (useSSl && ex.getMessage() != null && ex.getMessage().contains("SSLEngine")) {
                        log.debug("SSLEngine failure", ex);
                        connectedFuture = null;
                    } else {
                        try {
                            asyncHandler.onThrowable(ex);
                        } catch (Throwable t) {
                            log.warn("doConnect.writeRequest()", t);
                        }
                        IOException ioe = new IOException(ex.getMessage());
                        ioe.initCause(ex);
                        throw ioe;
                    }
                }
                return connectedFuture;
            }
        }

        // Do not throw an exception when we need an extra connection for a redirect.
        if (!reclaimCache && !connectionsPool.canCacheConnection()) {
            IOException ex = new IOException(String.format("Too many connections %s", config.getMaxTotalConnections()));
            try {
                asyncHandler.onThrowable(ex);
            } catch (Throwable t) {
                log.warn("!connectionsPool.canCacheConnection()", t);
            }
            throw ex;
        }

        boolean acquiredConnection = false;

        if (trackConnections) {
            if (!reclaimCache) {
                if (!freeConnections.tryAcquire()) {
                    IOException ex = new IOException(String.format("Too many connections %s", config.getMaxTotalConnections()));
                    try {
                        asyncHandler.onThrowable(ex);
                    } catch (Throwable t) {
                        log.warn("!connectionsPool.canCacheConnection()", t);
                    }
                    throw ex;
                } else {
                    acquiredConnection = true;
                }
            }
        }

        NettyConnectListener<T> c = new NettyConnectListener.Builder<T>(config, request, asyncHandler, f, this, bufferedBytes).build(uri);

        if (useSSl) {
            constructSSLPipeline(c);
        }

        ChannelFuture channelFuture;
        ClientBootstrap bootstrap = (request.getURI().getScheme().startsWith(WEBSOCKET) && !useProxy) ? (useSSl ? secureWebSocketBootstrap : webSocketBootstrap) : (useSSl ? secureBootstrap
                : plainBootstrap);
        bootstrap.setOption("connectTimeoutMillis", config.getConnectionTimeoutInMs());

        // Do no enable this with win.
        if (!System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("win")) {
            bootstrap.setOption("reuseAddress", providerConfig.getProperty(REUSE_ADDRESS));
        }

        try {
            InetSocketAddress remoteAddress;
            if (request.getInetAddress() != null) {
                remoteAddress = new InetSocketAddress(request.getInetAddress(), AsyncHttpProviderUtils.getPort(uri));
            } else if (!useProxy) {
                remoteAddress = new InetSocketAddress(AsyncHttpProviderUtils.getHost(uri), AsyncHttpProviderUtils.getPort(uri));
            } else {
                remoteAddress = new InetSocketAddress(proxyServer.getHost(), proxyServer.getPort());
            }

            if (request.getLocalAddress() != null) {
                channelFuture = bootstrap.connect(remoteAddress, new InetSocketAddress(request.getLocalAddress(), 0));
            } else {
                channelFuture = bootstrap.connect(remoteAddress);
            }

        } catch (Throwable t) {
            if (acquiredConnection) {
                freeConnections.release();
            }
            abort(c.future(), t.getCause() == null ? t : t.getCause());
            return c.future();
        }

        boolean directInvokation = !(IN_IO_THREAD.get() && DefaultChannelFuture.isUseDeadLockChecker());

        if (directInvokation && !asyncConnect && request.getFile() == null) {
            int timeOut = config.getConnectionTimeoutInMs() > 0 ? config.getConnectionTimeoutInMs() : Integer.MAX_VALUE;
            if (!channelFuture.awaitUninterruptibly(timeOut, TimeUnit.MILLISECONDS)) {
                if (acquiredConnection) {
                    freeConnections.release();
                }
                channelFuture.cancel();
                abort(c.future(), new ConnectException(String.format("Connect operation to %s timeout %s", uri, timeOut)));
            }

            try {
                c.operationComplete(channelFuture);
            } catch (Exception e) {
                if (acquiredConnection) {
                    freeConnections.release();
                }
                IOException ioe = new IOException(e.getMessage());
                ioe.initCause(e);
                try {
                    asyncHandler.onThrowable(ioe);
                } catch (Throwable t) {
                    log.warn("c.operationComplete()", t);
                }
                throw ioe;
            }
        } else {
            channelFuture.addListener(c);
        }

        log.debug("\nNon cached request \n{}\n\nusing Channel \n{}\n", c.future().getNettyRequest(), channelFuture.getChannel());

        if (!c.future().isCancelled() || !c.future().isDone()) {
            openChannels.add(channelFuture.getChannel());
            c.future().attachChannel(channelFuture.getChannel(), false);
        } else {
            if (acquiredConnection) {
                freeConnections.release();
            }
        }
        return c.future();
    }

    protected static int requestTimeoutInMs(AsyncHttpClientConfig config, PerRequestConfig perRequestConfig) {
        int result;
        if (perRequestConfig != null) {
            int prRequestTimeout = perRequestConfig.getRequestTimeoutInMs();
            result = (prRequestTimeout != 0 ? prRequestTimeout : config.getRequestTimeoutInMs());
        } else {
            result = config.getRequestTimeoutInMs();
        }
        return result;
    }

    private void closeChannel(final ChannelHandlerContext ctx) {
        connectionsPool.removeAll(ctx.getChannel());
        finishChannel(ctx);
    }

    private void finishChannel(final ChannelHandlerContext ctx) {
        ctx.setAttachment(new DiscardEvent());

        // The channel may have already been removed if a timeout occurred, and this method may be called just after.
        if (ctx.getChannel() == null) {
            return;
        }

        log.debug("Closing Channel {} ", ctx.getChannel());

        try {
            ctx.getChannel().close();
        } catch (Throwable t) {
            log.debug("Error closing a connection", t);
        }

        if (ctx.getChannel() != null) {
            openChannels.remove(ctx.getChannel());
        }

    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        // call super to reset the read timeout
        super.messageReceived(ctx, e);
        IN_IO_THREAD.set(Boolean.TRUE);
        if (ctx.getAttachment() == null) {
            log.debug("ChannelHandlerContext wasn't having any attachment");
        }

        if (ctx.getAttachment() instanceof DiscardEvent) {
            return;
        } else if (ctx.getAttachment() instanceof AsyncCallable) {
            if (e.getMessage() instanceof HttpChunk) {
                HttpChunk chunk = (HttpChunk) e.getMessage();
                if (chunk.isLast()) {
                    AsyncCallable ac = (AsyncCallable) ctx.getAttachment();
                    ac.call();
                } else {
                    return;
                }
            } else {
                AsyncCallable ac = (AsyncCallable) ctx.getAttachment();
                ac.call();
            }
            ctx.setAttachment(new DiscardEvent());
            return;
        } else if (!(ctx.getAttachment() instanceof NettyResponseFuture<?>)) {
            try {
                ctx.getChannel().close();
            } catch (Throwable t) {
                log.trace("Closing an orphan channel {}", ctx.getChannel());
            }
            return;
        }

        Protocol p = (ctx.getPipeline().get(HTTP_PROCESSOR) != null ? httpProtocol : webSocketProtocol);
        p.handle(ctx, e);
    }

    private Realm kerberosChallenge(List<String> proxyAuth, Request request, ProxyServer proxyServer, FluentCaseInsensitiveStringsMap headers, Realm realm,
            NettyResponseFuture<?> future, boolean proxyInd) throws NTLMEngineException {

        URI uri = request.getURI();
        String host = request.getVirtualHost() == null ? AsyncHttpProviderUtils.getHost(uri) : request.getVirtualHost();
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
            return realmBuilder.setUri(uri.getRawPath()).setMethodName(request.getMethod()).setScheme(Realm.AuthScheme.KERBEROS).build();
        } catch (Throwable throwable) {
            if (isNTLM(proxyAuth)) {
                return ntlmChallenge(proxyAuth, request, proxyServer, headers, realm, future, proxyInd);
            }
            abort(future, throwable);
            return null;
        }
    }

    private String authorizationHeaderName(boolean proxyInd) {
        return proxyInd? HttpHeaders.Names.PROXY_AUTHORIZATION: HttpHeaders.Names.AUTHORIZATION;
    }
    
    private void addNTLMAuthorization(FluentCaseInsensitiveStringsMap headers, String challengeHeader, boolean proxyInd) {
        headers.add(authorizationHeaderName(proxyInd), "NTLM " + challengeHeader);
    }

    private void addType3NTLMAuthorizationHeader(List<String> auth, FluentCaseInsensitiveStringsMap headers, String username, String password, String domain, String workstation, boolean proxyInd)
            throws NTLMEngineException {
        headers.remove(authorizationHeaderName(proxyInd));

        // Beware of space!, see #462
        if (isNonEmpty(auth) && auth.get(0).startsWith("NTLM ")) {
            String serverChallenge = auth.get(0).trim().substring("NTLM ".length());
            String challengeHeader = ntlmEngine.generateType3Msg(username, password, domain, workstation, serverChallenge);
            addNTLMAuthorization(headers, challengeHeader, proxyInd);
        }
    }

    private Realm ntlmChallenge(List<String> wwwAuth, Request request, ProxyServer proxyServer, FluentCaseInsensitiveStringsMap headers, Realm realm, NettyResponseFuture<?> future, boolean proxyInd)
            throws NTLMEngineException {

        boolean useRealm = (proxyServer == null && realm != null);

        String ntlmDomain = useRealm ? realm.getNtlmDomain() : proxyServer.getNtlmDomain();
        String ntlmHost = useRealm ? realm.getNtlmHost() : proxyServer.getHost();
        String principal = useRealm ? realm.getPrincipal() : proxyServer.getPrincipal();
        String password = useRealm ? realm.getPassword() : proxyServer.getPassword();

        Realm newRealm;
        if (realm != null && !realm.isNtlmMessageType2Received()) {
            String challengeHeader = ntlmEngine.generateType1Msg(ntlmDomain, ntlmHost);

            URI uri = request.getURI();
            addNTLMAuthorization(headers, challengeHeader, proxyInd);
            newRealm = new Realm.RealmBuilder().clone(realm).setScheme(realm.getAuthScheme()).setUri(uri.getRawPath()).setMethodName(request.getMethod())
                    .setNtlmMessageType2Received(true).build();
            future.getAndSetAuth(false);
        } else {
            addType3NTLMAuthorizationHeader(wwwAuth, headers, principal, password, ntlmDomain, ntlmHost, proxyInd);

            Realm.RealmBuilder realmBuilder;
            Realm.AuthScheme authScheme;
            if (realm != null) {
                realmBuilder = new Realm.RealmBuilder().clone(realm);
                authScheme = realm.getAuthScheme();
            } else {
                realmBuilder = new Realm.RealmBuilder();
                authScheme = Realm.AuthScheme.NTLM;
            }
            newRealm = realmBuilder.setScheme(authScheme).setUri(request.getURI().getPath()).setMethodName(request.getMethod()).build();
        }

        return newRealm;
    }

    private Realm ntlmProxyChallenge(List<String> wwwAuth, Request request, ProxyServer proxyServer, FluentCaseInsensitiveStringsMap headers, Realm realm,
            NettyResponseFuture<?> future) throws NTLMEngineException {
        future.getAndSetAuth(false);

        addType3NTLMAuthorizationHeader(wwwAuth, headers, proxyServer.getPrincipal(), proxyServer.getPassword(), proxyServer.getNtlmDomain(), proxyServer.getHost(), true);
        Realm newRealm;

        Realm.RealmBuilder realmBuilder = new Realm.RealmBuilder();
        if (realm != null) {
            realmBuilder = realmBuilder.clone(realm);
        }
        newRealm = realmBuilder.setUri(request.getURI().getPath()).setMethodName(request.getMethod()).build();

        return newRealm;
    }

    private String getPoolKey(NettyResponseFuture<?> future) {
        return getPoolKey(future.getURI(), future.getProxyServer(), future.getConnectionPoolKeyStrategy());
    }
    
    private String getPoolKey(URI uri, ProxyServer proxy, ConnectionPoolKeyStrategy strategy) {
        String serverPart = strategy.getKey(uri);
        return proxy != null ? proxy.getUrl() + serverPart : serverPart;
    }

    private void drainChannel(final ChannelHandlerContext ctx, final NettyResponseFuture<?> future) {
        ctx.setAttachment(new AsyncCallable(future) {
            public Object call() throws Exception {

                if (future.getKeepAlive() && ctx.getChannel().isReadable() && connectionsPool.offer(getPoolKey(future), ctx.getChannel())) {
                    return null;
                }

                finishChannel(ctx);
                return null;
            }

            @Override
            public String toString() {
                return String.format("Draining task for channel %s", ctx.getChannel());
            }
        });
    }

    private FilterContext handleIoException(FilterContext fc, NettyResponseFuture<?> future) {
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

    private void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, HttpResponse response, ChannelHandlerContext ctx) throws IOException {
        if (future.getAsyncHandler() instanceof AsyncHandlerExtensions) {
            AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();
        }
        final Request newRequest = fc.getRequest();
        future.setAsyncHandler(fc.getAsyncHandler());
        future.setState(NettyResponseFuture.STATE.NEW);
        future.touch();

        log.debug("\n\nReplaying Request {}\n for Future {}\n", newRequest, future);
        drainChannel(ctx, future);
        nextRequest(newRequest, future);
        return;
    }

    private List<String> getAuthorizationToken(List<Entry<String, String>> list, String headerAuth) {
        ArrayList<String> l = new ArrayList<String>();
        for (Entry<String, String> e : list) {
            if (e.getKey().equalsIgnoreCase(headerAuth)) {
                l.add(e.getValue().trim());
            }
        }
        return l;
    }

    private void nextRequest(final Request request, final NettyResponseFuture<?> future) throws IOException {
        nextRequest(request, future, true);
    }

    private void nextRequest(final Request request, final NettyResponseFuture<?> future, final boolean useCache) throws IOException {
        execute(request, future, useCache, true, true);
    }

    public void abort(NettyResponseFuture<?> future, Throwable t) {
        Channel channel = future.channel();
        if (channel != null && openChannels.contains(channel)) {
            closeChannel(channel.getPipeline().getContext(NettyAsyncHttpProvider.class));
            openChannels.remove(channel);
        }

        if (!future.isCancelled() && !future.isDone()) {
            log.debug("Aborting Future {}\n", future);
            log.debug(t.getMessage(), t);
        }

        future.abort(t);
    }

    private void upgradeProtocol(ChannelPipeline p, String scheme) throws IOException, GeneralSecurityException {
        if (p.get(HTTP_HANDLER) != null) {
            p.remove(HTTP_HANDLER);
        }

        if (isSecure(scheme)) {
            if (p.get(SSL_HANDLER) == null) {
                p.addFirst(HTTP_HANDLER, createHttpClientCodec());
                p.addFirst(SSL_HANDLER, new SslHandler(createSSLEngine()));
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

        connectionsPool.removeAll(ctx.getChannel());
        try {
            super.channelClosed(ctx, e);
        } catch (Exception ex) {
            log.trace("super.channelClosed", ex);
        }

        log.debug("Channel Closed: {} with attachment {}", e.getChannel(), ctx.getAttachment());

        if (ctx.getAttachment() instanceof AsyncCallable) {
            AsyncCallable ac = (AsyncCallable) ctx.getAttachment();
            ctx.setAttachment(ac.future());
            ac.call();
            return;
        }

        if (ctx.getAttachment() instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();
            future.touch();

            if (!config.getIOExceptionFilters().isEmpty()) {
                FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler()).request(future.getRequest())
                        .ioException(new IOException("Channel Closed")).build();
                fc = handleIoException(fc, future);

                if (fc.replayRequest() && !future.cannotBeReplay()) {
                    replayRequest(future, fc, null, ctx);
                    return;
                }
            }

            Protocol p = (ctx.getPipeline().get(HttpClientCodec.class) != null ? httpProtocol : webSocketProtocol);
            p.onClose(ctx, e);

            if (future != null && !future.isDone() && !future.isCancelled()) {
                if (remotelyClosed(ctx.getChannel(), future)) {
                    abort(future, REMOTELY_CLOSED_EXCEPTION);
                }
            } else {
                closeChannel(ctx);
            }
        }
    }

    protected boolean remotelyClosed(Channel channel, NettyResponseFuture<?> future) {

        if (isClose()) {
            return true;
        }

        connectionsPool.removeAll(channel);

        if (future == null) {
            Object attachment = channel.getPipeline().getContext(NettyAsyncHttpProvider.class).getAttachment();
            if (attachment instanceof NettyResponseFuture)
                future = (NettyResponseFuture<?>) attachment;
        }

        if (future == null || future.cannotBeReplay()) {
            log.debug("Unable to recover future {}\n", future);
            return true;
        }

        future.setState(NettyResponseFuture.STATE.RECONNECTED);

        log.debug("Trying to recover request {}\n", future.getNettyRequest());
        if (future.getAsyncHandler() instanceof AsyncHandlerExtensions) {
            AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();
        }

        try {
            nextRequest(future.getRequest(), future);
            return false;
        } catch (IOException iox) {
            future.setState(NettyResponseFuture.STATE.CLOSED);
            future.abort(iox);
            log.error("Remotely Closed, unable to recover", iox);
            return true;
        }
    }

    private void markAsDone(final NettyResponseFuture<?> future, final ChannelHandlerContext ctx) throws MalformedURLException {
        // We need to make sure everything is OK before adding the connection back to the pool.
        try {
            future.done();
        } catch (Throwable t) {
            // Never propagate exception once we know we are done.
            log.debug(t.getMessage(), t);
        }

        if (!future.getKeepAlive() || !ctx.getChannel().isReadable()) {
            closeChannel(ctx);
        }
    }

    private void finishUpdate(final NettyResponseFuture<?> future, final ChannelHandlerContext ctx, boolean lastValidChunk) throws IOException {
        if (lastValidChunk && future.getKeepAlive()) {
            drainChannel(ctx, future);
        } else {
            if (future.getKeepAlive() && ctx.getChannel().isReadable() && connectionsPool.offer(getPoolKey(future), ctx.getChannel())) {
                markAsDone(future, ctx);
                return;
            }
            finishChannel(ctx);
        }
        markAsDone(future, ctx);
    }

    @SuppressWarnings("unchecked")
    private final boolean updateStatusAndInterrupt(AsyncHandler handler, HttpResponseStatus c) throws Exception {
        return handler.onStatusReceived(c) != STATE.CONTINUE;
    }

    @SuppressWarnings("unchecked")
    private final boolean updateHeadersAndInterrupt(AsyncHandler handler, HttpResponseHeaders c) throws Exception {
        return handler.onHeadersReceived(c) != STATE.CONTINUE;
    }

    @SuppressWarnings("unchecked")
    private final boolean updateBodyAndInterrupt(final NettyResponseFuture<?> future, AsyncHandler handler, HttpResponseBodyPart c) throws Exception {
        boolean state = handler.onBodyPartReceived(c) != STATE.CONTINUE;
        if (c.closeUnderlyingConnection()) {
            future.setKeepAlive(false);
        }
        return state;
    }

    // Simple marker for stopping publishing bytes.

    final static class DiscardEvent {
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        Channel channel = e.getChannel();
        Throwable cause = e.getCause();
        NettyResponseFuture<?> future = null;

        if (e.getCause() instanceof PrematureChannelClosureException) {
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Unexpected I/O exception on channel {}", channel, cause);
        }

        try {

            if (cause instanceof ClosedChannelException) {
                return;
            }

            if (ctx.getAttachment() instanceof NettyResponseFuture<?>) {
                future = (NettyResponseFuture<?>) ctx.getAttachment();
                future.attachChannel(null, false);
                future.touch();

                if (cause instanceof IOException) {

                    if (!config.getIOExceptionFilters().isEmpty()) {
                        FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler()).request(future.getRequest())
                                .ioException(new IOException("Channel Closed")).build();
                        fc = handleIoException(fc, future);

                        if (fc.replayRequest()) {
                            replayRequest(future, fc, null, ctx);
                            return;
                        }
                    } else {
                        // Close the channel so the recovering can occurs.
                        try {
                            ctx.getChannel().close();
                        } catch (Throwable t) {
                            ; // Swallow.
                        }
                        return;
                    }
                }

                if (abortOnReadCloseException(cause) || abortOnWriteCloseException(cause)) {
                    log.debug("Trying to recover from dead Channel: {}", channel);
                    return;
                }
            } else if (ctx.getAttachment() instanceof AsyncCallable) {
                future = ((AsyncCallable) ctx.getAttachment()).future();
            }
        } catch (Throwable t) {
            cause = t;
        }

        if (future != null) {
            try {
                log.debug("Was unable to recover Future: {}", future);
                abort(future, cause);
            } catch (Throwable t) {
                log.error(t.getMessage(), t);
            }
        }

        Protocol p = (ctx.getPipeline().get(HttpClientCodec.class) != null ? httpProtocol : webSocketProtocol);
        p.onError(ctx, e);

        closeChannel(ctx);
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
                if (element.getClassName().equals("org.jboss.netty.handler.ssl.SslHandler") && element.getMethodName().equals("channelDisconnected")) {
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

    public static <T> NettyResponseFuture<T> newFuture(URI uri, Request request, AsyncHandler<T> asyncHandler, HttpRequest nettyRequest, AsyncHttpClientConfig config,
            NettyAsyncHttpProvider provider, ProxyServer proxyServer) {

        NettyResponseFuture<T> f = new NettyResponseFuture<T>(uri,//
                request,//
                asyncHandler,//
                nettyRequest,//
                requestTimeoutInMs(config, request.getPerRequestConfig()),//
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
        private final AsyncHandler asyncHandler;
        private final NettyResponseFuture<?> future;

        public ProgressListener(boolean notifyHeaders, AsyncHandler asyncHandler, NettyResponseFuture<?> future) {
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
                    log.debug(cause.getMessage(), cause);
                    try {
                        cf.getChannel().close();
                    } catch (RuntimeException ex) {
                        log.debug(ex.getMessage(), ex);
                    }
                    return;
                }

                if (cause instanceof ClosedChannelException || abortOnReadCloseException(cause) || abortOnWriteCloseException(cause)) {

                    if (log.isDebugEnabled()) {
                        log.debug(cf.getCause() == null ? "" : cf.getCause().getMessage(), cf.getCause());
                    }

                    try {
                        cf.getChannel().close();
                    } catch (RuntimeException ex) {
                        log.debug(ex.getMessage(), ex);
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
            Realm realm = future.getRequest().getRealm() != null ? future.getRequest().getRealm() : NettyAsyncHttpProvider.this.getConfig().getRealm();
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
                log.warn("Failed to close a file.", e);
            }

            try {
                raf.close();
            } catch (IOException e) {
                log.warn("Failed to close a file.", e);
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
                    log.error(e.getMessage(), e);
                }
            }
        }
    }

    protected AsyncHttpClientConfig getConfig() {
        return config;
    }

    private static class NonConnectionsPool implements ConnectionsPool<String, Channel> {

        public boolean offer(String uri, Channel connection) {
            return false;
        }

        public Channel poll(String uri) {
            return null;
        }

        public boolean removeAll(Channel connection) {
            return false;
        }

        public boolean canCacheConnection() {
            return true;
        }

        public void destroy() {
        }
    }

    private static final boolean validateWebSocketRequest(Request request, AsyncHandler<?> asyncHandler) {
        if (!("GET".equals(request.getMethod())) || !(asyncHandler instanceof WebSocketUpgradeHandler)) {
            return false;
        }
        return true;
    }

    private boolean redirect(Request request, NettyResponseFuture<?> future, HttpResponse response, final ChannelHandlerContext ctx) throws Exception {

        int statusCode = response.getStatus().getCode();
        boolean redirectEnabled = request.isRedirectOverrideSet() ? request.isRedirectEnabled() : config.isRedirectEnabled();
        if (redirectEnabled && (statusCode == 302 || statusCode == 301 || statusCode == 303 || statusCode == 307)) {

            if (future.incrementAndGetCurrentRedirectCount() < config.getMaxRedirects()) {
                // We must allow 401 handling again.
                future.getAndSetAuth(false);

                String location = response.getHeader(HttpHeaders.Names.LOCATION);
                URI uri = AsyncHttpProviderUtils.getRedirectUri(future.getURI(), location);
                boolean stripQueryString = config.isRemoveQueryParamOnRedirect();
                if (!uri.toString().equals(future.getURI().toString())) {
                    final RequestBuilder nBuilder = stripQueryString ? new RequestBuilder(future.getRequest()).setQueryParameters(null) : new RequestBuilder(future.getRequest());

                    if (!(statusCode < 302 || statusCode > 303) && !(statusCode == 302 && config.isStrict302Handling())) {
                        nBuilder.setMethod("GET");
                    }
                    final boolean initialConnectionKeepAlive = future.getKeepAlive();
                    final String initialPoolKey = getPoolKey(future);
                    future.setURI(uri);
                    String newUrl = uri.toString();
                    if (request.getURI().getScheme().startsWith(WEBSOCKET)) {
                        newUrl = newUrl.replace(HTTP, WEBSOCKET);
                    }

                    log.debug("Redirecting to {}", newUrl);
                    List<String> setCookieHeaders = future.getHttpResponse().getHeaders(HttpHeaders.Names.SET_COOKIE2);
                    if (!isNonEmpty(setCookieHeaders)) {
                        setCookieHeaders = future.getHttpResponse().getHeaders(HttpHeaders.Names.SET_COOKIE);
                    }

                    for (String cookieStr : setCookieHeaders) {
                        nBuilder.addOrReplaceCookie(CookieDecoder.decode(cookieStr));
                    }

                    AsyncCallable ac = new AsyncCallable(future) {
                        public Object call() throws Exception {
                            if (initialConnectionKeepAlive && ctx.getChannel().isReadable() && connectionsPool.offer(initialPoolKey, ctx.getChannel())) {
                                return null;
                            }
                            finishChannel(ctx);
                            return null;
                        }
                    };

                    if (response.isChunked()) {
                        // We must make sure there is no bytes left before executing the next request.
                        ctx.setAttachment(ac);
                    } else {
                        ac.call();
                    }
                    nextRequest(nBuilder.setUrl(newUrl).build(), future);
                    return true;
                }
            } else {
                throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
            }
        }
        return false;
    }

    private final String computeRealmURI(Realm realm, URI requestURI) throws URISyntaxException {
        if (realm.isUseAbsoluteURI()) {
            
            if (realm.isOmitQuery() && MiscUtil.isNonEmpty(requestURI.getQuery())) {
                return new URI(
                        requestURI.getScheme(),
                        requestURI.getAuthority(),
                        requestURI.getPath(),
                        null,
                        null).toString();
            } else {
                return requestURI.toString();
            }
        } else {
            if (realm.isOmitQuery() || !MiscUtil.isNonEmpty(requestURI.getQuery())) {
                return requestURI.getPath();
            } else {
                return requestURI.getPath() + "?" + requestURI.getQuery();
            }
        }
    }
    
    private final class HttpProtocol implements Protocol {
        // @Override
        public void handle(final ChannelHandlerContext ctx, final MessageEvent e) throws Exception {
            final NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();
            future.touch();

            // The connect timeout occured.
            if (future.isCancelled() || future.isDone()) {
                finishChannel(ctx);
                return;
            }

            HttpRequest nettyRequest = future.getNettyRequest();
            AsyncHandler handler = future.getAsyncHandler();
            Request request = future.getRequest();
            ProxyServer proxyServer = future.getProxyServer();
            HttpResponse response = null;
            try {
                if (e.getMessage() instanceof HttpResponse) {
                    response = (HttpResponse) e.getMessage();

                    log.debug("\n\nRequest {}\n\nResponse {}\n", nettyRequest, response);

                    // Required if there is some trailing headers.
                    future.setHttpResponse(response);

                    int statusCode = response.getStatus().getCode();

                    String ka = response.getHeader(HttpHeaders.Names.CONNECTION);
                    future.setKeepAlive(ka == null || ka.equalsIgnoreCase(HttpHeaders.Values.KEEP_ALIVE));

                    List<String> wwwAuth = getAuthorizationToken(response.getHeaders(), HttpHeaders.Names.WWW_AUTHENTICATE);
                    Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

                    HttpResponseStatus status = new ResponseStatus(future.getURI(), response, NettyAsyncHttpProvider.this);
                    HttpResponseHeaders responseHeaders = new ResponseHeaders(future.getURI(), response, NettyAsyncHttpProvider.this);
                    FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(request).responseStatus(status).responseHeaders(responseHeaders)
                            .build();

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
                    handler = fc.getAsyncHandler();
                    future.setAsyncHandler(handler);

                    // The request has changed
                    if (fc.replayRequest()) {
                        replayRequest(future, fc, response, ctx);
                        return;
                    }

                    final FluentCaseInsensitiveStringsMap headers = request.getHeaders();
                    final RequestBuilder builder = new RequestBuilder(future.getRequest());

                    // if (realm != null && !future.getURI().getPath().equalsIgnoreCase(realm.getUri())) {
                    // builder.setUrl(future.getURI().toString());
                    // }

                    if (statusCode == 401 && realm != null && !wwwAuth.isEmpty() && !future.getAndSetAuth(true)) {

                        future.setState(NettyResponseFuture.STATE.NEW);
                        Realm newRealm = null;

                        // NTLM
                        if (!wwwAuth.contains("Kerberos") && (isNTLM(wwwAuth) || (wwwAuth.contains("Negotiate")))) {
                            newRealm = ntlmChallenge(wwwAuth, request, proxyServer, headers, realm, future, false);
                            // SPNEGO KERBEROS
                        } else if (wwwAuth.contains("Negotiate")) {
                            newRealm = kerberosChallenge(wwwAuth, request, proxyServer, headers, realm, future, false);
                            if (newRealm == null)
                                return;
                        } else {
                            Realm.RealmBuilder realmBuilder = new Realm.RealmBuilder().clone(realm).setScheme(realm.getAuthScheme());
                            newRealm = realmBuilder.setUri(request.getURI().getPath()).setMethodName(request.getMethod()).setUsePreemptiveAuth(true)
                                    .parseWWWAuthenticateHeader(wwwAuth.get(0)).build();
                        }
                        
                        String realmURI = computeRealmURI(newRealm, request.getURI());
                        final Realm nr = new Realm.RealmBuilder().clone(newRealm).setUri(realmURI).build();

                        log.debug("Sending authentication to {}", request.getURI());
                        AsyncCallable ac = new AsyncCallable(future) {
                            public Object call() throws Exception {
                                drainChannel(ctx, future);
                                nextRequest(builder.setHeaders(headers).setRealm(nr).build(), future);
                                return null;
                            }
                        };

                        if (future.getKeepAlive() && response.isChunked()) {
                            // We must make sure there is no bytes left before executing the next request.
                            ctx.setAttachment(ac);
                        } else {
                            ac.call();
                        }
                        return;
                    }

                    if (statusCode == 100) {
                        future.getAndSetWriteHeaders(false);
                        future.getAndSetWriteBody(true);
                        writeRequest(ctx.getChannel(), config, future);
                        return;
                    }

                    List<String> proxyAuth = getAuthorizationToken(response.getHeaders(), HttpHeaders.Names.PROXY_AUTHENTICATE);
                    if (statusCode == 407 && realm != null && !proxyAuth.isEmpty() && !future.getAndSetAuth(true)) {

                        log.debug("Sending proxy authentication to {}", request.getURI());

                        future.setState(NettyResponseFuture.STATE.NEW);
                        Realm newRealm = null;

                        if (!proxyAuth.contains("Kerberos") && (isNTLM(proxyAuth) || (proxyAuth.contains("Negotiate")))) {
                            newRealm = ntlmProxyChallenge(proxyAuth, request, proxyServer, headers, realm, future);
                            // SPNEGO KERBEROS
                        } else if (proxyAuth.contains("Negotiate")) {
                            newRealm = kerberosChallenge(proxyAuth, request, proxyServer, headers, realm, future, true);
                            if (newRealm == null)
                                return;
                        } else {
                            newRealm = future.getRequest().getRealm();
                        }

                        Request req = builder.setHeaders(headers).setRealm(newRealm).build();
                        future.setReuseChannel(true);
                        future.setConnectAllowed(true);
                        nextRequest(req, future);
                        return;
                    }

                    if (future.getNettyRequest().getMethod().equals(HttpMethod.CONNECT) && statusCode == 200) {

                        log.debug("Connected to {}:{}", proxyServer.getHost(), proxyServer.getPort());

                        if (future.getKeepAlive()) {
                            future.attachChannel(ctx.getChannel(), true);
                        }

                        try {
                            String scheme = request.getURI().getScheme();
                            log.debug("Connecting to proxy {} for scheme {}", proxyServer, scheme);
                            upgradeProtocol(ctx.getChannel().getPipeline(), scheme);
                        } catch (Throwable ex) {
                            abort(future, ex);
                        }
                        Request req = builder.build();
                        future.setReuseChannel(true);
                        future.setConnectAllowed(false);
                        nextRequest(req, future);
                        return;
                    }

                    if (redirect(request, future, response, ctx))
                        return;

                    if (!future.getAndSetStatusReceived(true) && updateStatusAndInterrupt(handler, status)) {
                        finishUpdate(future, ctx, response.isChunked());
                        return;
                    } else if (!response.getHeaders().isEmpty() && updateHeadersAndInterrupt(handler, responseHeaders)) {
                        finishUpdate(future, ctx, response.isChunked());
                        return;
                    } else if (!response.isChunked()) {
                        updateBodyAndInterrupt(future, handler, new ResponseBodyPart(future.getURI(), response, NettyAsyncHttpProvider.this, true));
                        finishUpdate(future, ctx, false);
                        return;
                    }

                    if (nettyRequest.getMethod().equals(HttpMethod.HEAD)) {
                        updateBodyAndInterrupt(future, handler, new ResponseBodyPart(future.getURI(), response, NettyAsyncHttpProvider.this, true));
                        markAsDone(future, ctx);
                        drainChannel(ctx, future);
                    }

                } else if (e.getMessage() instanceof HttpChunk) {
                    HttpChunk chunk = (HttpChunk) e.getMessage();

                    if (handler != null) {
                        if (chunk.isLast()
                                || updateBodyAndInterrupt(future, handler, new ResponseBodyPart(future.getURI(), null, NettyAsyncHttpProvider.this, chunk, chunk.isLast()))) {
                            if (chunk instanceof DefaultHttpChunkTrailer) {
                                updateHeadersAndInterrupt(handler, new ResponseHeaders(future.getURI(), future.getHttpResponse(), NettyAsyncHttpProvider.this,
                                        (HttpChunkTrailer) chunk));
                            }
                            finishUpdate(future, ctx, !chunk.isLast());
                        }
                    }
                }
            } catch (Exception t) {
                if (t instanceof IOException && !config.getIOExceptionFilters().isEmpty()) {
                    FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler()).request(future.getRequest())
                            .ioException(IOException.class.cast(t)).build();
                    fc = handleIoException(fc, future);

                    if (fc.replayRequest()) {
                        replayRequest(future, fc, response, ctx);
                        return;
                    }
                }

                try {
                    abort(future, t);
                } finally {
                    finishUpdate(future, ctx, false);
                    throw t;
                }
            }
        }

        // @Override
        public void onError(ChannelHandlerContext ctx, ExceptionEvent e) {
        }

        // @Override
        public void onClose(ChannelHandlerContext ctx, ChannelStateEvent e) {
        }
    }

    private final class WebSocketProtocol implements Protocol {
        private static final byte OPCODE_CONT = 0x0;
        private static final byte OPCODE_TEXT = 0x1;
        private static final byte OPCODE_BINARY = 0x2;
        private static final byte OPCODE_PING = 0x9;
        private static final byte OPCODE_PONG = 0xa;
        private static final byte OPCODE_UNKNOWN = -1;

        // We don't need to synchronize as replacing the "ws-decoder" will process using the same thread.
        private void invokeOnSucces(ChannelHandlerContext ctx, WebSocketUpgradeHandler h) {
            if (!h.touchSuccess()) {
                try {
                    h.onSuccess(new NettyWebSocket(ctx.getChannel()));
                } catch (Exception ex) {
                    NettyAsyncHttpProvider.this.log.warn("onSuccess unexexpected exception", ex);
                }
            }
        }

        // @Override
        public void handle(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
            NettyResponseFuture future = NettyResponseFuture.class.cast(ctx.getAttachment());
            WebSocketUpgradeHandler h = WebSocketUpgradeHandler.class.cast(future.getAsyncHandler());
            Request request = future.getRequest();

            if (e.getMessage() instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) e.getMessage();

                HttpResponseStatus s = new ResponseStatus(future.getURI(), response, NettyAsyncHttpProvider.this);
                HttpResponseHeaders responseHeaders = new ResponseHeaders(future.getURI(), response, NettyAsyncHttpProvider.this);
                FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(h).request(request).responseStatus(s).responseHeaders(responseHeaders).build();
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
                    replayRequest(future, fc, response, ctx);
                    return;
                }

                future.setHttpResponse(response);
                if (redirect(request, future, response, ctx))
                    return;

                final org.jboss.netty.handler.codec.http.HttpResponseStatus status = new org.jboss.netty.handler.codec.http.HttpResponseStatus(101, "Web Socket Protocol Handshake");

                final boolean validStatus = response.getStatus().equals(status);
                final boolean validUpgrade = response.getHeader(HttpHeaders.Names.UPGRADE) != null;
                String c = response.getHeader(HttpHeaders.Names.CONNECTION);
                if (c == null) {
                    c = response.getHeader("connection");
                }

                final boolean validConnection = c == null ? false : c.equalsIgnoreCase(HttpHeaders.Values.UPGRADE);

                s = new ResponseStatus(future.getURI(), response, NettyAsyncHttpProvider.this);
                final boolean statusReceived = h.onStatusReceived(s) == STATE.UPGRADE;

                if (!statusReceived) {
                    try {
                        h.onCompleted();
                    } finally {
                        future.done();
                    }
                    return;
                }

                final boolean headerOK = h.onHeadersReceived(responseHeaders) == STATE.CONTINUE;
                if (!headerOK || !validStatus || !validUpgrade || !validConnection) {
                    abort(future, new IOException("Invalid handshake response"));
                    return;
                }

                String accept = response.getHeader(HttpHeaders.Names.SEC_WEBSOCKET_ACCEPT);
                String key = WebSocketUtil.getAcceptKey(future.getNettyRequest().getHeader(HttpHeaders.Names.SEC_WEBSOCKET_KEY));
                if (accept == null || !accept.equals(key)) {
                    abort(future, new IOException(String.format("Invalid challenge. Actual: %s. Expected: %s", accept, key)));
                    return;
                }

                ctx.getPipeline().replace(HTTP_HANDLER, "ws-encoder", new WebSocket08FrameEncoder(true));
                ctx.getPipeline().addBefore(WS_PROCESSOR, "ws-decoder", new WebSocket08FrameDecoder(false, false));

                invokeOnSucces(ctx, h);
                future.done();
            } else if (e.getMessage() instanceof WebSocketFrame) {

                invokeOnSucces(ctx, h);

                final WebSocketFrame frame = (WebSocketFrame) e.getMessage();

                byte pendingOpcode = OPCODE_UNKNOWN;
                if (frame instanceof TextWebSocketFrame) {
                    pendingOpcode = OPCODE_TEXT;
                } else if (frame instanceof BinaryWebSocketFrame) {
                    pendingOpcode = OPCODE_BINARY;
                } else if (frame instanceof PingWebSocketFrame) {
                    pendingOpcode = OPCODE_PING;
                } else if (frame instanceof PongWebSocketFrame) {
                    pendingOpcode = OPCODE_PONG;
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
                    h.onBodyPartReceived(rp);

                    NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());

                    if (webSocket != null) {
                        if (pendingOpcode == OPCODE_BINARY) {
                            webSocket.onBinaryFragment(rp.getBodyPartBytes(), frame.isFinalFragment());
                        } else if (pendingOpcode == OPCODE_TEXT) {
                            webSocket.onTextFragment(frame.getBinaryData().toString(UTF8), frame.isFinalFragment());
                        } else if (pendingOpcode == OPCODE_PING) {
                            webSocket.onPing(rp.getBodyPartBytes());
                        } else if (pendingOpcode == OPCODE_PONG) {
                            webSocket.onPong(rp.getBodyPartBytes());
                        }

                        if (frame instanceof CloseWebSocketFrame) {
                            try {
                                ctx.setAttachment(DiscardEvent.class);
                                webSocket.onClose(CloseWebSocketFrame.class.cast(frame).getStatusCode(), CloseWebSocketFrame.class.cast(frame).getReasonText());
                            } catch (Throwable t) {
                                // Swallow any exception that may comes from a Netty version released before 3.4.0
                                log.trace("", t);
                            } finally {
                                h.resetSuccess();
                            }
                        }
                    } else {
                        log.debug("UpgradeHandler returned a null NettyWebSocket ");
                    }
                }
            } else {
                log.error("Invalid attachment {}", ctx.getAttachment());
            }
        }

        // @Override
        public void onError(ChannelHandlerContext ctx, ExceptionEvent e) {
            try {
                log.warn("onError {}", e);
                if (!(ctx.getAttachment() instanceof NettyResponseFuture)) {
                    return;
                }

                NettyResponseFuture<?> nettyResponse = NettyResponseFuture.class.cast(ctx.getAttachment());
                WebSocketUpgradeHandler h = WebSocketUpgradeHandler.class.cast(nettyResponse.getAsyncHandler());

                NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());
                if (webSocket != null) {
                    webSocket.onError(e.getCause());
                    webSocket.close();
                }
            } catch (Throwable t) {
                log.error("onError", t);
            }
        }

        // @Override
        public void onClose(ChannelHandlerContext ctx, ChannelStateEvent e) {
            log.trace("onClose {}", e);
            if (!(ctx.getAttachment() instanceof NettyResponseFuture)) {
                return;
            }

            try {
                NettyResponseFuture<?> nettyResponse = NettyResponseFuture.class.cast(ctx.getAttachment());
                WebSocketUpgradeHandler h = WebSocketUpgradeHandler.class.cast(nettyResponse.getAsyncHandler());
                NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());
                h.resetSuccess();

                log.trace("Connection was closed abnormally (that is, with no close frame being sent).");
                if (!(ctx.getAttachment() instanceof DiscardEvent) && webSocket != null)
                    webSocket.close(1006, "Connection was closed abnormally (that is, with no close frame being sent).");
            } catch (Throwable t) {
                log.error("onError", t);
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

    private static boolean isSecure(URI uri) {
        return isSecure(uri.getScheme());
    }
}
