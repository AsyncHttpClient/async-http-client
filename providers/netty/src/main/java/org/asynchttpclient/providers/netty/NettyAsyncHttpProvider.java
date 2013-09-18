/*
 * Copyright 2010-2013 Ning, Inc.
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
package org.asynchttpclient.providers.netty;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.STATE;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.Body;
import org.asynchttpclient.BodyGenerator;
import org.asynchttpclient.ConnectionPoolKeyStrategy;
import org.asynchttpclient.ConnectionsPool;
import org.asynchttpclient.Cookie;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.MaxRedirectException;
import org.asynchttpclient.ProgressAsyncHandler;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.RandomAccessBody;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.generators.InputStreamBodyGenerator;
import org.asynchttpclient.listener.TransferCompletionHandler;
import org.asynchttpclient.listener.TransferCompletionHandler.TransferAdapter;
import org.asynchttpclient.multipart.MultipartBody;
import org.asynchttpclient.multipart.MultipartRequestEntity;
import org.asynchttpclient.ntlm.NTLMEngine;
import org.asynchttpclient.ntlm.NTLMEngineException;
import org.asynchttpclient.org.jboss.netty.handler.codec.http.CookieDecoder;
import org.asynchttpclient.org.jboss.netty.handler.codec.http.CookieEncoder;
import org.asynchttpclient.providers.netty.FeedableBodyGenerator.FeedListener;
import org.asynchttpclient.providers.netty.spnego.SpnegoEngine;
import org.asynchttpclient.providers.netty.util.CleanupChannelGroup;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.asynchttpclient.util.AuthenticatorUtils;
import org.asynchttpclient.util.ProxyUtils;
import org.asynchttpclient.util.SslUtils;
import org.asynchttpclient.util.UTF8UrlEncoder;
import org.asynchttpclient.websocket.WebSocketUpgradeHandler;
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
import org.jboss.netty.handler.codec.http.HttpContentCompressor;
import org.jboss.netty.handler.codec.http.HttpContentDecompressor;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseDecoder;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.asynchttpclient.util.AsyncHttpProviderUtils.DEFAULT_CHARSET;
import static org.asynchttpclient.util.DateUtil.millisTime;
import static org.asynchttpclient.util.MiscUtil.isNonEmpty;
import static org.jboss.netty.channel.Channels.pipeline;

public class NettyAsyncHttpProvider extends SimpleChannelUpstreamHandler implements AsyncHttpProvider {
    private final static String HTTP_HANDLER = "httpHandler";
    protected final static String SSL_HANDLER = "sslHandler";
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
    private final NettyAsyncHttpProviderConfig asyncHttpProviderConfig;
    private boolean executeConnectAsync = true;
    public static final ThreadLocal<Boolean> IN_IO_THREAD = new ThreadLocalBoolean();
    private final boolean trackConnections;
    private final boolean useRawUrl;
    private final static NTLMEngine ntlmEngine = new NTLMEngine();
    private static SpnegoEngine spnegoEngine = null;
    private final Protocol httpProtocol = new HttpProtocol();
    private final Protocol webSocketProtocol = new WebSocketProtocol();
    private final boolean managedExecutorService;
    private ExecutorService service;

	private static boolean isNTLM(List<String> auth) {
		return isNonEmpty(auth) && auth.get(0).startsWith("NTLM");
	}

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {

        if (config.getAsyncHttpProviderConfig() instanceof NettyAsyncHttpProviderConfig) {
            asyncHttpProviderConfig = NettyAsyncHttpProviderConfig.class.cast(config.getAsyncHttpProviderConfig());
        } else {
            asyncHttpProviderConfig = new NettyAsyncHttpProviderConfig();
        }
        service = config.executorService();
        managedExecutorService = (service == null);
        if (service == null) {
            service = AsyncHttpProviderUtils.createDefaultExecutorService();
        }

        if (asyncHttpProviderConfig.isUseBlockingIO()) {
            socketChannelFactory = new OioClientSocketChannelFactory(service);
            this.allowReleaseSocketChannelFactory = true;
        } else {
            // check if external NioClientSocketChannelFactory is defined
            NioClientSocketChannelFactory scf = asyncHttpProviderConfig.getSocketChannelFactory();
            if (scf != null) {
                this.socketChannelFactory = scf;

                // cannot allow releasing shared channel factory
                this.allowReleaseSocketChannelFactory = false;
            } else {
                ExecutorService e = asyncHttpProviderConfig.getBossExecutorService();
                if (e == null) {
                    e = Executors.newCachedThreadPool();
                }
                int numWorkers = config.getIoThreadMultiplier() * Runtime.getRuntime().availableProcessors();
                log.debug("Number of application's worker threads is {}", numWorkers);
                socketChannelFactory = new NioClientSocketChannelFactory(e, service, numWorkers);
                this.allowReleaseSocketChannelFactory = true;
            }
        }
        plainBootstrap = new ClientBootstrap(socketChannelFactory);
        secureBootstrap = new ClientBootstrap(socketChannelFactory);
        webSocketBootstrap = new ClientBootstrap(socketChannelFactory);
        secureWebSocketBootstrap = new ClientBootstrap(socketChannelFactory);
        this.config = config;

        configureNetty();

        // This is dangerous as we can't catch a wrong typed ConnectionsPool
        ConnectionsPool<String, Channel> cp = (ConnectionsPool<String, Channel>) config.getConnectionsPool();
        if (cp == null && config.getAllowPoolingConnection()) {
            cp = new NettyConnectionsPool(this);
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
    }

    @Override
    public String toString() {
        return String.format("NettyAsyncHttpProvider:\n\t- maxConnections: %d\n\t- openChannels: %s\n\t- connectionPools: %s", config.getMaxTotalConnections() - freeConnections.availablePermits(), openChannels.toString(), connectionsPool.toString());
    }

    void configureNetty() {
        if (asyncHttpProviderConfig != null) {
            for (Entry<String, Object> entry : asyncHttpProviderConfig.propertiesSet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                plainBootstrap.setOption(key, value);
                webSocketBootstrap.setOption(key, value);
                secureBootstrap.setOption(key, value);
                secureWebSocketBootstrap.setOption(key, value);
            }
        }

        plainBootstrap.setPipelineFactory(createPlainPipelineFactory());
        DefaultChannelFuture.setUseDeadLockChecker(false);

        if (asyncHttpProviderConfig != null) {
            executeConnectAsync = config.isAsyncConnectMode();
            if (!executeConnectAsync) {
                DefaultChannelFuture.setUseDeadLockChecker(true);
            }
        }

        webSocketBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            /* @Override */
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast("http-decoder", new HttpResponseDecoder());
                pipeline.addLast("http-encoder", new HttpRequestEncoder());
                pipeline.addLast("httpProcessor", NettyAsyncHttpProvider.this);
                return pipeline;
            }
        });
    }

    protected HttpClientCodec newHttpClientCodec() {
        if (asyncHttpProviderConfig != null) {
            return new HttpClientCodec(asyncHttpProviderConfig.getMaxInitialLineLength(), asyncHttpProviderConfig.getMaxHeaderSize(), asyncHttpProviderConfig.getMaxChunkSize(), false);

        } else {
            return new HttpClientCodec();
        }
    }

    protected ChannelPipelineFactory createPlainPipelineFactory() {
        return new ChannelPipelineFactory() {

            /* @Override */
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();

                pipeline.addLast(HTTP_HANDLER, newHttpClientCodec());

                if (config.getRequestCompressionLevel() > 0) {
                    pipeline.addLast("deflater", new HttpContentCompressor(config.getRequestCompressionLevel()));
                }

                if (config.isCompressionEnabled()) {
                    pipeline.addLast("inflater", new HttpContentDecompressor());
                }
                pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                pipeline.addLast("httpProcessor", NettyAsyncHttpProvider.this);
                return pipeline;
            }
        };
    }

    void constructSSLPipeline(final NettyConnectListener<?> cl) {

        secureBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            /* @Override */
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();

                try {
                    pipeline.addLast(SSL_HANDLER, new SslHandler(createSSLEngine()));
                } catch (Throwable ex) {
                    abort(cl.future(), ex);
                }

                pipeline.addLast(HTTP_HANDLER, newHttpClientCodec());

                if (config.isCompressionEnabled()) {
                    pipeline.addLast("inflater", new HttpContentDecompressor());
                }
                pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                pipeline.addLast("httpProcessor", NettyAsyncHttpProvider.this);
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

                pipeline.addLast("http-decoder", new HttpResponseDecoder());
                pipeline.addLast("http-encoder", new HttpRequestEncoder());
                pipeline.addLast("httpProcessor", NettyAsyncHttpProvider.this);

                return pipeline;
            }
        });
    }

    private Channel lookupInCache(URI uri, ConnectionPoolKeyStrategy connectionPoolKeyStrategy) {
        final Channel channel = connectionsPool.poll(connectionPoolKeyStrategy.getKey(uri));

        if (channel != null) {
            log.debug("Using cached Channel {}\n for uri {}\n", channel, uri);

            try {
                // Always make sure the channel who got cached support the proper protocol. It could
                // only occurs when a HttpMethod.CONNECT is used against a proxy that require upgrading from http to
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

    protected final <T> void writeRequest(final Channel channel, final AsyncHttpClientConfig config, final NettyResponseFuture<T> future, final HttpRequest nettyRequest) {
        try {
            /**
             * If the channel is dead because it was pooled and the remote server decided to close it, we just let it go and the closeChannel do it's work.
             */
            if (!channel.isOpen() || !channel.isConnected()) {
                return;
            }

            Body body = null;
            if (!future.getNettyRequest().getMethod().equals(HttpMethod.CONNECT)) {
                BodyGenerator bg = future.getRequest().getBodyGenerator();
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
                    String length = nettyRequest.getHeader(HttpHeaders.Names.CONTENT_LENGTH);
                    body = new MultipartBody(future.getRequest().getParts(), contentType, length);
                }
            }

            if (future.getAsyncHandler() instanceof TransferCompletionHandler) {

                FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
                for (String s : future.getNettyRequest().getHeaderNames()) {
                    for (String header : future.getNettyRequest().getHeaders(s)) {
                        h.add(s, header);
                    }
                }

                TransferCompletionHandler.class.cast(future.getAsyncHandler()).transferAdapter(new TransferAdapter(h));
            }

            // Leave it to true.
            if (future.getAndSetWriteHeaders(true)) {
                try {
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
                if (!future.getNettyRequest().getMethod().equals(HttpMethod.CONNECT)) {

                    if (future.getRequest().getFile() != null) {
                        final File file = future.getRequest().getFile();
                        long fileLength = 0;
                        final RandomAccessFile raf = new RandomAccessFile(file, "r");

                        try {
                            fileLength = raf.length();

                            ChannelFuture writeFuture;
                            if (channel.getPipeline().get(SslHandler.class) != null) {
                                writeFuture = channel.write(new ChunkedFile(raf, 0, fileLength, MAX_BUFFERED_BYTES));
                            } else {
                                final FileRegion region = new OptimizedFileRegion(raf, 0, fileLength);
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

                        ChannelFuture writeFuture;
                        if (channel.getPipeline().get(SslHandler.class) == null && (body instanceof RandomAccessBody)) {
                            BodyFileRegion bodyFileRegion = new BodyFileRegion((RandomAccessBody) body);
                            writeFuture = channel.write(bodyFileRegion);
                        } else {
                            BodyChunkedInput bodyChunkedInput = new BodyChunkedInput(body);
                            BodyGenerator bg = future.getRequest().getBodyGenerator();
                            if (bg instanceof FeedableBodyGenerator) {
                                ((FeedableBodyGenerator) bg).setListener(new FeedListener() {
                                    @Override
                                    public void onContentAdded() {
                                        channel.getPipeline().get(ChunkedWriteHandler.class).resumeTransfer();
                                    }
                                });
                            }
                            writeFuture = channel.write(bodyChunkedInput);
                        }

                        final Body b = body;
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
            int requestTimeout = AsyncHttpProviderUtils.requestTimeout(config, future.getRequest());
            int schedulePeriod = requestTimeout != -1 ? (config.getIdleConnectionTimeoutInMs() != -1 ? Math.min(requestTimeout, config.getIdleConnectionTimeoutInMs()) : requestTimeout) : config.getIdleConnectionTimeoutInMs();

            if (schedulePeriod != -1 && !future.isDone() && !future.isCancelled()) {
                ReaperFuture reaperFuture = new ReaperFuture(future);
                Future<?> scheduledFuture = config.reaper().scheduleAtFixedRate(reaperFuture, 0, schedulePeriod, TimeUnit.MILLISECONDS);
                reaperFuture.setScheduledFuture(scheduledFuture);
                future.setReaperFuture(reaperFuture);
            }
        } catch (RejectedExecutionException ex) {
            abort(future, ex);
        }

    }

    protected final static HttpRequest buildRequest(AsyncHttpClientConfig config, Request request, URI uri, boolean allowConnect, ChannelBuffer buffer, ProxyServer proxyServer) throws IOException {

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

    private static HttpRequest construct(AsyncHttpClientConfig config, Request request, HttpMethod m, URI uri, ChannelBuffer buffer, ProxyServer proxyServer) throws IOException {

        String host = null;
        boolean webSocket = isWebSocket(uri);

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
            if (proxyServer != null && !(isSecure(uri) && config.isUseRelativeURIsWithSSLProxies()))
                path = uri.toString();
            else if (uri.getRawQuery() != null)
                path = uri.getRawPath() + "?" + uri.getRawQuery();
            else
                path = uri.getRawPath();
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, m, path);
        }

        if (webSocket) {
            nettyRequest.addHeader(HttpHeaders.Names.UPGRADE, HttpHeaders.Values.WEBSOCKET);
            nettyRequest.addHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.UPGRADE);
            nettyRequest.addHeader(HttpHeaders.Names.ORIGIN, "http://" + uri.getHost() + ":" + (uri.getPort() == -1 ? isSecure(uri.getScheme()) ? 443 : 80 : uri.getPort()));
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
            FluentCaseInsensitiveStringsMap h = request.getHeaders();
            if (h != null) {
                for (Entry<String, List<String>> header : h) {
                    String name = header.getKey();
                    if (!HttpHeaders.Names.HOST.equalsIgnoreCase(name)) {
                        for (String value : header.getValue()) {
                            nettyRequest.addHeader(name, value);
                        }
                    }
                }
            }

            if (config.isCompressionEnabled()) {
                nettyRequest.setHeader(HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP);
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
                nettyRequest.setHeader(HttpHeaders.Names.AUTHORIZATION, AuthenticatorUtils.computeBasicAuthentication(realm));
                break;
            case DIGEST:
                if (isNonEmpty(realm.getNonce())) {
                    try {
                        nettyRequest.setHeader(HttpHeaders.Names.AUTHORIZATION, AuthenticatorUtils.computeDigestAuthentication(realm));
                    } catch (NoSuchAlgorithmException e) {
                        throw new SecurityException(e);
                    }
                }
                break;
            case NTLM:
                try {
                    String msg = ntlmEngine.generateType1Msg("NTLM " + domain, authHost);
                    nettyRequest.setHeader(HttpHeaders.Names.AUTHORIZATION, "NTLM " + msg);
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
                nettyRequest.setHeader(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);
                break;
            case NONE:
                break;
            default:
                throw new IllegalStateException("Invalid Authentication " + realm);
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
        if (request.getHeaders().getFirstValue(HttpHeaders.Names.ACCEPT) == null) {
            nettyRequest.setHeader(HttpHeaders.Names.ACCEPT, "*/*");
        }

        String userAgentHeader = request.getHeaders().getFirstValue(HttpHeaders.Names.USER_AGENT);
        if (userAgentHeader != null) {
            nettyRequest.setHeader(HttpHeaders.Names.USER_AGENT, userAgentHeader);
        } else if (config.getUserAgent() != null) {
            nettyRequest.setHeader(HttpHeaders.Names.USER_AGENT, config.getUserAgent());
        } else {
            nettyRequest.setHeader(HttpHeaders.Names.USER_AGENT, AsyncHttpProviderUtils.constructUserAgent(NettyAsyncHttpProvider.class, config));
        }

        if (!m.equals(HttpMethod.CONNECT)) {
            if (isNonEmpty(request.getCookies())) {
                nettyRequest.setHeader(HttpHeaders.Names.COOKIE, CookieEncoder.encodeClientSide(request.getCookies(), config.isRfc6265CookieEncoding()));
            }

            String reqType = request.getMethod();
            if (!"HEAD".equals(reqType) && !"OPTION".equals(reqType) && !"TRACE".equals(reqType)) {

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
                } else if (request.getStreamData() != null) {
                    int[] lengthWrapper = new int[1];
                    // FIXME should be streaming instead!
                    byte[] bytes = AsyncHttpProviderUtils.readFully(request.getStreamData(), lengthWrapper);
                    int length = lengthWrapper[0];
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(length));
                    nettyRequest.setContent(ChannelBuffers.wrappedBuffer(bytes, 0, length));
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
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(mre.getContentLength()));

                } else if (request.getFile() != null) {
                    File file = request.getFile();
                    if (!file.isFile()) {
                        throw new IOException(String.format("File %s is not a file or doesn't exist", file.getAbsolutePath()));
                    }
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, file.length());
                }
            }
        }
        return nettyRequest;
    }

    public void close() {
        isClose.set(true);
        try {
            connectionsPool.destroy();
            openChannels.close();

            for (Channel channel : openChannels) {
                ChannelHandlerContext ctx = channel.getPipeline().getContext(NettyAsyncHttpProvider.class);
                if (ctx.getAttachment() instanceof NettyResponseFuture<?>) {
                    NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();
                    future.setReaperFuture(null);
                }
            }

            if (managedExecutorService) {
                service.shutdown();
            }
            config.reaper().shutdown();
            if (this.allowReleaseSocketChannelFactory) {
                socketChannelFactory.releaseExternalResources();
                plainBootstrap.releaseExternalResources();
                secureBootstrap.releaseExternalResources();
                webSocketBootstrap.releaseExternalResources();
                secureWebSocketBootstrap.releaseExternalResources();
            }
        } catch (Throwable t) {
            log.warn("Unexpected error on close", t);
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

    private <T> ListenableFuture<T> doConnect(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> f, boolean useCache, boolean asyncConnect, boolean reclaimCache) throws IOException {

        if (isClose.get()) {
            throw new IOException("Closed");
        }

        if (request.getUrl().startsWith(WEBSOCKET) && !validateWebSocketRequest(request, asyncHandler)) {
            throw new IOException("WebSocket method must be a GET");
        }

        ProxyServer proxyServer = ProxyUtils.getProxyServer(config, request);
        boolean useProxy = proxyServer != null;
        URI uri;
        if (useRawUrl) {
            uri = request.getRawURI();
        } else {
            uri = request.getURI();
        }
        Channel channel = null;

        if (useCache) {
            if (f != null && f.reuseChannel() && f.channel() != null) {
                channel = f.channel();
            } else {
                URI connectionKeyUri = useProxy ? proxyServer.getURI() : uri;
                channel = lookupInCache(connectionKeyUri, request.getConnectionPoolKeyStrategy());
            }
        }

        ChannelBuffer bufferedBytes = null;
        if (f != null && f.getRequest().getFile() == null && !f.getNettyRequest().getMethod().getName().equals(HttpMethod.CONNECT.getName())) {
            bufferedBytes = f.getNettyRequest().getContent();
        }

        boolean useSSl = isSecure(uri) && !useProxy;
        if (channel != null && channel.isOpen() && channel.isConnected()) {
            HttpRequest nettyRequest = null;

            if (f == null) {
            	nettyRequest = buildRequest(config, request, uri, false, bufferedBytes, proxyServer);
                f = newFuture(uri, request, asyncHandler, nettyRequest, config, this, proxyServer);
            } else {
                nettyRequest = buildRequest(config, request, uri, f.isConnectAllowed(), bufferedBytes, proxyServer);
                f.setNettyRequest(nettyRequest);
            }
            f.setState(NettyResponseFuture.STATE.POOLED);
            f.attachChannel(channel, false);

            log.debug("\nUsing cached Channel {}\n for request \n{}\n", channel, nettyRequest);
            channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(f);

            try {
                writeRequest(channel, config, f, nettyRequest);
            } catch (Exception ex) {
                log.debug("writeRequest failure", ex);
                if (useSSl && ex.getMessage() != null && ex.getMessage().contains("SSLEngine")) {
                    log.debug("SSLEngine failure", ex);
                    f = null;
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
            return f;
        }

        // Do not throw an exception when we need an extra connection for a redirect.
        if (!reclaimCache && !connectionsPool.canCacheConnection()) {
            IOException ex = new IOException("Too many connections " + config.getMaxTotalConnections());
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
                    IOException ex = new IOException("Too many connections " + config.getMaxTotalConnections());
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
        boolean avoidProxy = ProxyUtils.avoidProxy(proxyServer, uri.getHost());

        if (useSSl) {
            constructSSLPipeline(c);
        }

        ChannelFuture channelFuture;
        ClientBootstrap bootstrap = request.getUrl().startsWith(WEBSOCKET) ? (useSSl ? secureWebSocketBootstrap : webSocketBootstrap) : (useSSl ? secureBootstrap : plainBootstrap);
        bootstrap.setOption("connectTimeoutMillis", config.getConnectionTimeoutInMs());

        try {
            InetSocketAddress remoteAddress;
            if (request.getInetAddress() != null) {
                remoteAddress = new InetSocketAddress(request.getInetAddress(), AsyncHttpProviderUtils.getPort(uri));
            } else if (proxyServer == null || avoidProxy) {
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
        }
        return c.future();
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

        Protocol p = (ctx.getPipeline().get(HttpClientCodec.class) != null ? httpProtocol : webSocketProtocol);
        p.handle(ctx, e);
    }

    private Realm kerberosChallenge(List<String> proxyAuth, Request request, ProxyServer proxyServer, FluentCaseInsensitiveStringsMap headers, Realm realm, NettyResponseFuture<?> future) throws NTLMEngineException {

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
                return ntlmChallenge(proxyAuth, request, proxyServer, headers, realm, future);
            }
            abort(future, throwable);
            return null;
        }
    }

	private void addType3NTLMAuthorizationHeader(List<String> auth, FluentCaseInsensitiveStringsMap headers, String username, String password, String domain, String workstation)
	        throws NTLMEngineException {
		headers.remove(HttpHeaders.Names.AUTHORIZATION);

		if (isNTLM(auth)) {
			String serverChallenge = auth.get(0).trim().substring("NTLM ".length());
			String challengeHeader = ntlmEngine.generateType3Msg(username, password, domain, workstation, serverChallenge);

			headers.add(HttpHeaders.Names.AUTHORIZATION, "NTLM " + challengeHeader);
		}
	}

    private Realm ntlmChallenge(List<String> wwwAuth, Request request, ProxyServer proxyServer, FluentCaseInsensitiveStringsMap headers, Realm realm, NettyResponseFuture<?> future) throws NTLMEngineException {

        boolean useRealm = (proxyServer == null && realm != null);

        String ntlmDomain = useRealm ? realm.getNtlmDomain() : proxyServer.getNtlmDomain();
        String ntlmHost = useRealm ? realm.getNtlmHost() : proxyServer.getHost();
        String principal = useRealm ? realm.getPrincipal() : proxyServer.getPrincipal();
        String password = useRealm ? realm.getPassword() : proxyServer.getPassword();

        Realm newRealm;
        if (realm != null && !realm.isNtlmMessageType2Received()) {
            String challengeHeader = ntlmEngine.generateType1Msg(ntlmDomain, ntlmHost);

            URI uri = request.getURI();
            headers.add(HttpHeaders.Names.AUTHORIZATION, "NTLM " + challengeHeader);
            newRealm = new Realm.RealmBuilder().clone(realm).setScheme(realm.getAuthScheme()).setUri(uri.getRawPath()).setMethodName(request.getMethod()).setNtlmMessageType2Received(true).build();
            future.getAndSetAuth(false);
        } else {
        	addType3NTLMAuthorizationHeader(wwwAuth, headers, principal, password, ntlmDomain, ntlmHost);

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

    private Realm ntlmProxyChallenge(List<String> wwwAuth, Request request, ProxyServer proxyServer, FluentCaseInsensitiveStringsMap headers, Realm realm, NettyResponseFuture<?> future) throws NTLMEngineException {
        future.getAndSetAuth(false);
        headers.remove(HttpHeaders.Names.PROXY_AUTHORIZATION);

        addType3NTLMAuthorizationHeader(wwwAuth, headers, proxyServer.getPrincipal(), proxyServer.getPassword(), proxyServer.getNtlmDomain(), proxyServer.getHost());

        Realm newRealm;
        Realm.RealmBuilder realmBuilder;
        if (realm != null) {
            realmBuilder = new Realm.RealmBuilder().clone(realm);
        } else {
            realmBuilder = new Realm.RealmBuilder();
        }
        newRealm = realmBuilder// .setScheme(realm.getAuthScheme())
                .setUri(request.getURI().getPath()).setMethodName(request.getMethod()).build();

        return newRealm;
    }

    private String getPoolKey(NettyResponseFuture<?> future) throws MalformedURLException {
        URI uri = future.getProxyServer() != null ? future.getProxyServer().getURI() : future.getURI();
        return future.getConnectionPoolKeyStrategy().getKey(uri);
    }

    private void drainChannel(final ChannelHandlerContext ctx, final NettyResponseFuture<?> future) {
        ctx.setAttachment(new AsyncCallable(future) {
            public Object call() throws Exception {
                if (future.isKeepAlive() && ctx.getChannel().isReadable() && connectionsPool.offer(getPoolKey(future), ctx.getChannel())) {
                    return null;
                }

                finishChannel(ctx);
                return null;
            }

            @Override
            public String toString() {
                return "Draining task for channel " + ctx.getChannel();
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

    private void abort(NettyResponseFuture<?> future, Throwable t) {
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
                p.addFirst(HTTP_HANDLER, newHttpClientCodec());
                p.addFirst(SSL_HANDLER, new SslHandler(createSSLEngine()));
            } else {
                p.addAfter(SSL_HANDLER, HTTP_HANDLER, newHttpClientCodec());
            }

        } else {
            p.addFirst(HTTP_HANDLER, newHttpClientCodec());
        }
    }

    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {

        if (isClose.get()) {
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
                FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler()).request(future.getRequest()).ioException(new IOException("Channel Closed")).build();
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
                    abort(future, new IOException("Remotely Closed"));
                }
            } else {
                closeChannel(ctx);
            }
        }
    }

    protected boolean remotelyClosed(Channel channel, NettyResponseFuture<?> future) {

        if (isClose.get()) {
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
        future.getAndSetStatusReceived(false);

        log.debug("Trying to recover request {}\n", future.getNettyRequest());

        try {
            nextRequest(future.getRequest(), future);
            return false;
        } catch (IOException iox) {
            future.setState(NettyResponseFuture.STATE.CLOSED);
            future.abort(iox);
            log.error("Remotely Closed, unable to recover", iox);
        }
        return true;
    }

    private void markAsDone(final NettyResponseFuture<?> future, final ChannelHandlerContext ctx) throws MalformedURLException {
        // We need to make sure everything is OK before adding the connection back to the pool.
        try {
            future.done();
        } catch (Throwable t) {
            // Never propagate exception once we know we are done.
            log.debug(t.getMessage(), t);
        }

        if (!future.isKeepAlive() || !ctx.getChannel().isReadable()) {
            closeChannel(ctx);
        }
    }

    private void finishUpdate(final NettyResponseFuture<?> future, final ChannelHandlerContext ctx, boolean lastValidChunk) throws IOException {
        if (lastValidChunk && future.isKeepAlive()) {
            drainChannel(ctx, future);
        } else {
            if (future.isKeepAlive() && ctx.getChannel().isReadable() && connectionsPool.offer(getPoolKey(future), ctx.getChannel())) {
                markAsDone(future, ctx);
                return;
            }
            finishChannel(ctx);
        }
        markAsDone(future, ctx);
    }

    private final boolean updateStatusAndInterrupt(AsyncHandler<?> handler, HttpResponseStatus c) throws Exception {
        return handler.onStatusReceived(c) != STATE.CONTINUE;
    }

    private final boolean updateHeadersAndInterrupt(AsyncHandler<?> handler, HttpResponseHeaders c) throws Exception {
        return handler.onHeadersReceived(c) != STATE.CONTINUE;
    }

    private final boolean updateBodyAndInterrupt(final NettyResponseFuture<?> future, AsyncHandler<?> handler, HttpResponseBodyPart c) throws Exception {
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
                        FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler()).request(future.getRequest()).ioException(new IOException("Channel Closed")).build();
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
            return abortOnReadCloseException(cause.getCause());
        }

        return false;
    }

    private final static int getPredefinedContentLength(Request request, HttpRequest r) {
        int length = (int) request.getContentLength();
        if (length == -1 && r.getHeader(HttpHeaders.Names.CONTENT_LENGTH) != null) {
            length = Integer.valueOf(r.getHeader(HttpHeaders.Names.CONTENT_LENGTH));
        }

        return length;
    }

    public static <T> NettyResponseFuture<T> newFuture(URI uri, Request request, AsyncHandler<T> asyncHandler, HttpRequest nettyRequest, AsyncHttpClientConfig config, NettyAsyncHttpProvider provider, ProxyServer proxyServer) {

        int requestTimeout = AsyncHttpProviderUtils.requestTimeout(config, request);
        NettyResponseFuture<T> f = new NettyResponseFuture<T>(uri,//
                request,//
                asyncHandler,//
                nettyRequest,//
                requestTimeout,//
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
             * We need to make sure we aren't in the middle of an authorization process before publishing events as we will re-publish again the same event after the authorization, causing unpredictable behavior.
             */
            Realm realm = future.getRequest().getRealm() != null ? future.getRequest().getRealm() : NettyAsyncHttpProvider.this.getConfig().getRealm();
            boolean startPublishing = future.isInAuth() || realm == null || realm.getUsePreemptiveAuth() == true;

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

    /**
     * Because some implementation of the ThreadSchedulingService do not clean up cancel task until they try to run them, we wrap the task with the future so the when the NettyResponseFuture cancel the reaper future this wrapper will release the references to the channel and the
     * nettyResponseFuture immediately. Otherwise, the memory referenced this way will only be released after the request timeout period which can be arbitrary long.
     */
    private final class ReaperFuture implements Future, Runnable {
        private Future scheduledFuture;
        private NettyResponseFuture<?> nettyResponseFuture;

        public ReaperFuture(NettyResponseFuture<?> nettyResponseFuture) {
            this.nettyResponseFuture = nettyResponseFuture;
        }

        public void setScheduledFuture(Future scheduledFuture) {
            this.scheduledFuture = scheduledFuture;
        }

        /**
         * @Override
         */
        public boolean cancel(boolean mayInterruptIfRunning) {
            nettyResponseFuture = null;
            return scheduledFuture.cancel(mayInterruptIfRunning);
        }

        /**
         * @Override
         */
        public Object get() throws InterruptedException, ExecutionException {
            return scheduledFuture.get();
        }

        /**
         * @Override
         */
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return scheduledFuture.get(timeout, unit);
        }

        /**
         * @Override
         */
        public boolean isCancelled() {
            return scheduledFuture.isCancelled();
        }

        /**
         * @Override
         */
        public boolean isDone() {
            return scheduledFuture.isDone();
        }

        private void expire(String message) {
            log.debug("{} for {}", message, nettyResponseFuture);
            abort(nettyResponseFuture, new TimeoutException(message));
            nettyResponseFuture = null;
        }

        /**
         * @Override
         */
        public synchronized void run() {
            if (isClose.get()) {
                cancel(true);
                return;
            }

            boolean futureDone = nettyResponseFuture.isDone();
            boolean futureCanceled = nettyResponseFuture.isCancelled();

            if (nettyResponseFuture != null && !futureDone && !futureCanceled) {
                long now = millisTime();
                if (nettyResponseFuture.hasRequestTimedOut(now)) {
                    long age = now - nettyResponseFuture.getStart();
                    expire("Request reached time out of " + nettyResponseFuture.getRequestTimeoutInMs() + " ms after " + age + " ms");
                } else if (nettyResponseFuture.hasConnectionIdleTimedOut(now)) {
                    long age = now - nettyResponseFuture.getStart();
                    expire("Request reached idle time out of " + nettyResponseFuture.getIdleConnectionTimeoutInMs() + " ms after " + age + " ms");
                }

            } else if (nettyResponseFuture == null || futureDone || futureCanceled) {
                cancel(true);
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
        if (request.getMethod() != "GET" || !(asyncHandler instanceof WebSocketUpgradeHandler)) {
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
                    final boolean initialConnectionKeepAlive = future.isKeepAlive();
                    final String initialPoolKey = getPoolKey(future);
                    future.setURI(uri);
                    String newUrl = uri.toString();
                    if (request.getUrl().startsWith(WEBSOCKET)) {
                        newUrl = newUrl.replace(HTTP, WEBSOCKET);
                    }

                    log.debug("Redirecting to {}", newUrl);
                    for (String cookieStr : future.getHttpResponse().getHeaders(HttpHeaders.Names.SET_COOKIE)) {
                        for (Cookie c : CookieDecoder.decode(cookieStr)) {
                            nBuilder.addOrReplaceCookie(c);
                        }
                    }

                    for (String cookieStr : future.getHttpResponse().getHeaders(HttpHeaders.Names.SET_COOKIE2)) {
                        for (Cookie c : CookieDecoder.decode(cookieStr)) {
                            nBuilder.addOrReplaceCookie(c);
                        }
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
                    future.setKeepAlive(ka == null || !ka.toLowerCase(Locale.ENGLISH).equals("close"));

                    List<String> wwwAuth = getAuthorizationToken(response.getHeaders(), HttpHeaders.Names.WWW_AUTHENTICATE);
                    Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

                    HttpResponseStatus status = new ResponseStatus(future.getURI(), response, NettyAsyncHttpProvider.this);
                    HttpResponseHeaders responseHeaders = new ResponseHeaders(future.getURI(), response, NettyAsyncHttpProvider.this);
                    FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(request).responseStatus(status).responseHeaders(responseHeaders).build();

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

                    Realm newRealm = null;
                    final FluentCaseInsensitiveStringsMap headers = request.getHeaders();
                    final RequestBuilder builder = new RequestBuilder(future.getRequest());

                    // if (realm != null && !future.getURI().getPath().equalsIgnoreCase(realm.getUri())) {
                    // builder.setUrl(future.getURI().toString());
                    // }

                    if (statusCode == 401 && realm != null && !wwwAuth.isEmpty() && !future.getAndSetAuth(true)) {

                        future.setState(NettyResponseFuture.STATE.NEW);
                        // NTLM
                        if (!wwwAuth.contains("Kerberos") && (isNTLM(wwwAuth) || (wwwAuth.contains("Negotiate")))) {
                            newRealm = ntlmChallenge(wwwAuth, request, proxyServer, headers, realm, future);
                            // SPNEGO KERBEROS
                        } else if (wwwAuth.contains("Negotiate")) {
                            newRealm = kerberosChallenge(wwwAuth, request, proxyServer, headers, realm, future);
                            if (newRealm == null)
                                return;
                        } else {
                            newRealm = new Realm.RealmBuilder().clone(realm).setScheme(realm.getAuthScheme()).setUri(request.getURI().getPath()).setMethodName(request.getMethod()).setUsePreemptiveAuth(true).parseWWWAuthenticateHeader(wwwAuth.get(0)).build();
                        }

                        final Realm nr = new Realm.RealmBuilder().clone(newRealm).setUri(URI.create(request.getUrl()).getPath()).build();

                        log.debug("Sending authentication to {}", request.getUrl());
                        AsyncCallable ac = new AsyncCallable(future) {
                            public Object call() throws Exception {
                                drainChannel(ctx, future);
                                nextRequest(builder.setHeaders(headers).setRealm(nr).build(), future);
                                return null;
                            }
                        };

                        if (future.isKeepAlive() && response.isChunked()) {
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
                        writeRequest(ctx.getChannel(), config, future, nettyRequest);
                        return;
                    }

                    List<String> proxyAuth = getAuthorizationToken(response.getHeaders(), HttpHeaders.Names.PROXY_AUTHENTICATE);
                    if (statusCode == 407 && realm != null && !proxyAuth.isEmpty() && !future.getAndSetAuth(true)) {

                        log.debug("Sending proxy authentication to {}", request.getUrl());

                        future.setState(NettyResponseFuture.STATE.NEW);

                        if (!proxyAuth.contains("Kerberos") && (isNTLM(proxyAuth) || (proxyAuth.contains("Negotiate")))) {
                            newRealm = ntlmProxyChallenge(proxyAuth, request, proxyServer, headers, realm, future);
                            // SPNEGO KERBEROS
                        } else if (proxyAuth.contains("Negotiate")) {
                            newRealm = kerberosChallenge(proxyAuth, request, proxyServer, headers, realm, future);
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

                        if (future.isKeepAlive()) {
                            future.attachChannel(ctx.getChannel(), true);
                        }

                        try {
                            log.debug("Connecting to proxy {} for scheme {}", proxyServer, request.getUrl());
                            upgradeProtocol(ctx.getChannel().getPipeline(), request.getURI().getScheme());
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
                    } else if (updateHeadersAndInterrupt(handler, responseHeaders)) {
                        finishUpdate(future, ctx, response.isChunked());
                        return;
                    } else if (!response.isChunked()) {
                        if (response.getContent().readableBytes() != 0) {
                            updateBodyAndInterrupt(future, handler, new ResponseBodyPart(future.getURI(), response, NettyAsyncHttpProvider.this, true));
                        }
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
                        if (chunk.isLast() || updateBodyAndInterrupt(future, handler, new ResponseBodyPart(future.getURI(), null, NettyAsyncHttpProvider.this, chunk, chunk.isLast()))) {
                            if (chunk instanceof DefaultHttpChunkTrailer) {
                                updateHeadersAndInterrupt(handler, new ResponseHeaders(future.getURI(), future.getHttpResponse(), NettyAsyncHttpProvider.this, (HttpChunkTrailer) chunk));
                            }
                            finishUpdate(future, ctx, !chunk.isLast());
                        }
                    }
                }
            } catch (Exception t) {
                if (t instanceof IOException && !config.getIOExceptionFilters().isEmpty()) {
                    FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler()).request(future.getRequest()).ioException(IOException.class.cast(t)).build();
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
        private static final byte OPCODE_UNKNOWN = -1;
        protected byte pendingOpcode = OPCODE_UNKNOWN;

        // We don't need to synchronize as replacing the "ws-decoder" will process using the same thread.
        private void invokeOnSucces(ChannelHandlerContext ctx, WebSocketUpgradeHandler h) {
            if (!h.touchSuccess()) {
                try {
                    h.onSuccess(new NettyWebSocket(ctx.getChannel()));
                } catch (Exception ex) {
                    NettyAsyncHttpProvider.log.warn("onSuccess unexexpected exception", ex);
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
                    c = response.getHeader(HttpHeaders.Names.CONNECTION.toLowerCase(Locale.ENGLISH));
                }

                final boolean validConnection = c == null ? false : c.equalsIgnoreCase(HttpHeaders.Values.UPGRADE);

                s = new ResponseStatus(future.getURI(), response, NettyAsyncHttpProvider.this);
                final boolean statusReceived = h.onStatusReceived(s) == STATE.UPGRADE;

                final boolean headerOK = h.onHeadersReceived(responseHeaders) == STATE.CONTINUE;
                if (!headerOK || !validStatus || !validUpgrade || !validConnection || !statusReceived) {
                    abort(future, new IOException("Invalid handshake response"));
                    return;
                }

                String accept = response.getHeader(HttpHeaders.Names.SEC_WEBSOCKET_ACCEPT);
                String key = WebSocketUtil.getAcceptKey(future.getNettyRequest().getHeader(HttpHeaders.Names.SEC_WEBSOCKET_KEY));
                if (accept == null || !accept.equals(key)) {
                    throw new IOException(String.format("Invalid challenge. Actual: %s. Expected: %s", accept, key));
                }

                ctx.getPipeline().replace("http-encoder", "ws-encoder", new WebSocket08FrameEncoder(true));
                ctx.getPipeline().get(HttpResponseDecoder.class).replace("ws-decoder", new WebSocket08FrameDecoder(false, false));

                invokeOnSucces(ctx, h);
                future.done();
            } else if (e.getMessage() instanceof WebSocketFrame) {

                invokeOnSucces(ctx, h);

                final WebSocketFrame frame = (WebSocketFrame) e.getMessage();

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
                    h.onBodyPartReceived(rp);

                    NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());

                    if (webSocket != null) {
                        if (pendingOpcode == OPCODE_BINARY) {
                            webSocket.onBinaryFragment(rp.getBodyPartBytes(), frame.isFinalFragment());
                        } else {
                            webSocket.onTextFragment(frame.getBinaryData().toString(UTF8), frame.isFinalFragment());
                        }

                        if (frame instanceof CloseWebSocketFrame) {
                            try {
                                ctx.setAttachment(DiscardEvent.class);
                                webSocket.onClose(CloseWebSocketFrame.class.cast(frame).getStatusCode(), CloseWebSocketFrame.class.cast(frame).getReasonText());
                            } catch (Throwable t) {
                                // Swallow any exception that may comes from a Netty version released before 3.4.0
                                log.trace("", t);
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

                if (!(ctx.getAttachment() instanceof DiscardEvent))
                    webSocket.close(1006, "Connection was closed abnormally (that is, with no close frame being sent).");
            } catch (Throwable t) {
                log.error("onError", t);
            }
        }
    }

    private static boolean isWebSocket(URI uri) {
        return WEBSOCKET.equalsIgnoreCase(uri.getScheme()) || WEBSOCKET_SSL.equalsIgnoreCase(uri.getScheme());
    }

    private static boolean isSecure(String scheme) {
        return HTTPS.equalsIgnoreCase(scheme) || WEBSOCKET_SSL.equalsIgnoreCase(scheme);
    }

    private static boolean isSecure(URI uri) {
        return isSecure(uri.getScheme());
    }
}
