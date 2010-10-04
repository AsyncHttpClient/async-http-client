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

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHandler.STATE;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.AsyncHttpProviderConfig;
import com.ning.http.client.ByteArrayPart;
import com.ning.http.client.ConnectionsPool;
import com.ning.http.client.Cookie;
import com.ning.http.client.FilePart;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.FluentStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.Part;
import com.ning.http.client.PerRequestConfig;
import com.ning.http.client.ProgressAsyncHandler;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.StringPart;
import com.ning.http.client.logging.LogManager;
import com.ning.http.client.logging.Logger;
import com.ning.http.multipart.ByteArrayPartSource;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.multipart.PartSource;
import com.ning.http.util.AuthenticatorUtils;
import com.ning.http.util.SslUtils;
import com.ning.http.util.UTF8UrlEncoder;
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
import org.jboss.netty.channel.DefaultFileRegion;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.FileRegion;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.CookieEncoder;
import org.jboss.netty.handler.codec.http.DefaultCookie;
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
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedFile;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.handler.timeout.IdleState;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.util.HashedWheelTimer;

import javax.net.ssl.SSLEngine;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.jboss.netty.channel.Channels.pipeline;

public class NettyAsyncHttpProvider extends IdleStateHandler implements AsyncHttpProvider<HttpResponse> {
    private final static String HTTP_HANDLER = "httpHandler";
    private final static String SSL_HANDLER = "sslHandler";

    private final static Logger log = LogManager.getLogger(NettyAsyncHttpProvider.class);

    private final ClientBootstrap plainBootstrap;

    private final ClientBootstrap secureBootstrap;

    private final static int MAX_BUFFERED_BYTES = 8192;

    private final AsyncHttpClientConfig config;

    private final AtomicBoolean isClose = new AtomicBoolean(false);

    private final NioClientSocketChannelFactory socketChannelFactory;

    private final ChannelGroup openChannels = new DefaultChannelGroup("asyncHttpClient");

    private final ConnectionsPool<String, Channel> connectionsPool;

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {
        super(new HashedWheelTimer(), 0, 0, config.getIdleConnectionTimeoutInMs(), TimeUnit.MILLISECONDS);
        socketChannelFactory = new NioClientSocketChannelFactory(
                Executors.newCachedThreadPool(),
                config.executorService());
        plainBootstrap = new ClientBootstrap(socketChannelFactory);
        secureBootstrap = new ClientBootstrap(socketChannelFactory);

        this.config = config;

        // This is dangerous as we can't catch a wrong typed ConnectionsPool
        ConnectionsPool<String, Channel> cp = (ConnectionsPool<String, Channel>) config.getConnectionsPool();
        if (cp == null) {
            cp = new NettyConnectionsPool(config);
        }
        this.connectionsPool = cp;

        AsyncHttpProviderConfig<?, ?> providerConfig = config.getAsyncHttpProviderConfig();
        if (providerConfig != null && NettyAsyncHttpProviderConfig.class.isAssignableFrom(providerConfig.getClass())) {
            configureNetty(NettyAsyncHttpProviderConfig.class.cast(providerConfig));
        }
    }

    void configureNetty(NettyAsyncHttpProviderConfig providerConfig) {
        for (Entry<String, Object> entry : providerConfig.propertiesSet()) {
            plainBootstrap.setOption(entry.getKey(), entry.getValue());
            secureBootstrap.setOption(entry.getKey(), entry.getValue());
        }
    }

    void configure(final ConnectListener<?> cl) {

        plainBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            /* @Override */
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();

                pipeline.addLast(HTTP_HANDLER, new HttpClientCodec());

                if (config.isCompressionEnabled()) {
                    pipeline.addLast("inflater", new HttpContentDecompressor());
                }
                pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                pipeline.addLast("httpProcessor", NettyAsyncHttpProvider.this);
                return pipeline;
            }
        });

        secureBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            /* @Override */
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();

                try {
                    pipeline.addLast(SSL_HANDLER, new SslHandler(createSSLEngine()));
                } catch (Throwable ex) {
                    cl.future().abort(ex);
                }

                pipeline.addLast(HTTP_HANDLER, new HttpClientCodec());

                if (config.isCompressionEnabled()) {
                    pipeline.addLast("inflater", new HttpContentDecompressor());
                }
                pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                pipeline.addLast("httpProcessor", NettyAsyncHttpProvider.this);
                return pipeline;
            }
        });
    }

    private Channel lookupInCache(URI uri) {
        Channel channel = connectionsPool.removeConnection(getBaseUrl(uri));

        if (channel != null) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("[" + Thread.currentThread().getName() + "] Using cached Channel %s", uri, channel));
            }
            /**
             * The Channel will eventually be closed by Netty and will becomes invalid.
             * We might suffer a memory leak if we don't scan for closed channel. The
             * AsyncHttpClientConfig.reaper() will always make sure those are cleared.
             */
            if (channel.isOpen()) {
                channel.setReadable(true);
            } else {
                return null;
            }

            try {
                // Always make sure the channel who got cached support the proper protocol. It could
                // only occurs when a HttpMethod.CONNECT is used agains a proxy that require upgrading from http to
                // https.
                return verifyChannelPipeline(channel, uri.getScheme());
            } catch (Exception ex) {
                if (log.isDebugEnabled()) {
                    log.warn(ex);
                }
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

        if (channel.getPipeline().get(SSL_HANDLER) != null && "http".equalsIgnoreCase(scheme)) {
            channel.getPipeline().remove(SSL_HANDLER);
        } else if (channel.getPipeline().get(HTTP_HANDLER) != null && "http".equalsIgnoreCase(scheme)) {
            return channel;
        } else if (channel.getPipeline().get(SSL_HANDLER) == null && "https".equalsIgnoreCase(scheme)) {
            channel.getPipeline().addFirst(SSL_HANDLER, new SslHandler(createSSLEngine()));
        }
        return channel;
    }

    protected final <T> void executeRequest(final Channel channel,
                                          final AsyncHttpClientConfig config,
                                          final NettyResponseFuture<T> future,
                                          final HttpRequest nettyRequest) throws ConnectException {

        if (!channel.isConnected()) {
            String url = channel.getRemoteAddress() != null ? channel.getRemoteAddress().toString() : null;
            if (url == null) {
                try {
                    url = future.getURI().toString();
                } catch (MalformedURLException e) {
                    // ignored
                }
            }
            throw new ConnectException(String.format("[" + Thread.currentThread().getName() + "] Connection refused to %s", url));
        }

        channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(future);

        channel.write(nettyRequest).addListener(new ProgressListener(true, future.getAsyncHandler()));

        if (future.getRequest().getFile() != null) {
            final File file = future.getRequest().getFile();
            RandomAccessFile raf;
            long fileLength = 0;

            try {
                raf = new RandomAccessFile(file, "r");
                fileLength = raf.length();

                ChannelFuture writeFuture;
                if (channel.getPipeline().get(SslHandler.class) != null) {
                    writeFuture = channel.write(new ChunkedFile(raf, 0, fileLength, 8192));
                    writeFuture.addListener(new ProgressListener(false, future.getAsyncHandler()));
                } else {
                    final FileRegion region = new DefaultFileRegion(raf.getChannel(), 0, fileLength);
                    writeFuture = channel.write(region);
                    writeFuture.addListener(new ProgressListener(false, future.getAsyncHandler()) {
                        public void operationComplete(ChannelFuture cf) {
                            region.releaseExternalResources();
                            super.operationComplete(cf);
                        }
                    });
                }
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        try {
            future.touch();
            int delay = requestTimeout(config, future.getRequest().getPerRequestConfig());
            if (delay != -1) {
                future.setReaperFuture(config.reaper().scheduleAtFixedRate(new Runnable() {
                    public void run() {
                        if (future.hasExpired()) {
                            if (log.isDebugEnabled()) {
                                log.debug("Request Timeout expired for " + future);
                            }
                            future.abort(new TimeoutException("Request timed out."));
                            markChannelNotReadable(channel.getPipeline().getContext(NettyAsyncHttpProvider.class));
                        }
                    }
                }, 0, delay, TimeUnit.MILLISECONDS));
            }
        } catch (RejectedExecutionException ex) {
            future.abort(ex);
        }
    }

    protected final static HttpRequest buildRequest(AsyncHttpClientConfig config, Request request, URI uri, boolean allowConnect) throws IOException {

        String method = request.getReqType();
        if (allowConnect && ((request.getProxyServer() != null || config.getProxyServer() != null) && "https".equalsIgnoreCase(uri.getScheme()))) {
            method = HttpMethod.CONNECT.toString();
        }
        return construct(config, request, new HttpMethod(method), uri);
    }

    protected final static URI createUri(String u) {
        URI uri = URI.create(u);
        final String scheme = uri.getScheme().toLowerCase();
        if (scheme == null || !scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("The URI scheme, of the URI " + u
                    + ", must be equal (ignoring case) to 'http'");
        }

        String path = uri.getPath();
        if (path == null) {
            throw new IllegalArgumentException("The URI path, of the URI " + uri
                    + ", must be non-null");
        } else if (path.length() > 0 && path.charAt(0) != '/') {
            throw new IllegalArgumentException("The URI path, of the URI " + uri
                    + ". must start with a '/'");
        } else if (path.length() == 0) {
            return URI.create(u + "/");
        }

        return uri;
    }

    @SuppressWarnings("deprecation")
    private static HttpRequest construct(AsyncHttpClientConfig config,
                                         Request request,
                                         HttpMethod m,
                                         URI uri) throws IOException {
        String host = uri.getHost();

        if (request.getVirtualHost() != null) {
            host = request.getVirtualHost();
        }

        HttpRequest nettyRequest;
        if (m.equals(HttpMethod.CONNECT)) {
            uri = URI.create(new StringBuilder(uri.getHost())
                    .append(":")
                    .append(getPort(uri)).toString());
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_0, m, uri.toString());
        } else if (config.getProxyServer() != null || request.getProxyServer() != null) {
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, m, uri.toString());
        } else {
            StringBuilder path = new StringBuilder(uri.getRawPath());
            if (uri.getQuery() != null) {
                path.append("?").append(uri.getRawQuery());
            }
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, m, path.toString());
        }
        if (uri.getPort() == -1) {
            nettyRequest.setHeader(HttpHeaders.Names.HOST, host);
        } else {
            nettyRequest.setHeader(HttpHeaders.Names.HOST, host + ":" + uri.getPort());
        }

        if (!m.equals(HttpMethod.CONNECT)) {
            FluentCaseInsensitiveStringsMap h = request.getHeaders();
            if (h != null) {
                for (String name : h.keySet()) {
                    if (!"host".equalsIgnoreCase(name)) {
                        for (String value : h.get(name)) {
                            nettyRequest.addHeader(name, value);
                        }
                    }
                }
            }

            if (config.isCompressionEnabled()) {
                nettyRequest.setHeader(HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP);
            }

        }

        Realm realm = request.getRealm();
        if (realm != null && realm.getUsePreemptiveAuth()) {
            switch (realm.getAuthScheme()) {
                case BASIC:
                    nettyRequest.setHeader(HttpHeaders.Names.AUTHORIZATION,
                            AuthenticatorUtils.computeBasicAuthentication(realm));
                    break;
                case DIGEST:
                    if (realm.getNonce() != null && !realm.getNonce().equals("")) {
                        try {
                            nettyRequest.setHeader(HttpHeaders.Names.AUTHORIZATION,
                                    AuthenticatorUtils.computeDigestAuthentication(realm));
                        } catch (NoSuchAlgorithmException e) {
                            throw new SecurityException(e);
                        }
                    }
                    break;
                default:
                    throw new IllegalStateException(String.format("[" + Thread.currentThread().getName() + "] Invalid Authentication %s", realm.toString()));
            }
        }

        String ka = config.getKeepAlive() ? "keep-alive" : "close";
        nettyRequest.setHeader(HttpHeaders.Names.CONNECTION, ka);
        ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
        if (proxyServer != null) {
            nettyRequest.setHeader("Proxy-Connection", ka);
            if (proxyServer.getPrincipal() != null) {
                nettyRequest.setHeader(HttpHeaders.Names.PROXY_AUTHORIZATION, AuthenticatorUtils.computeBasicAuthentication(proxyServer));
            }
        }
        
        // Add default accept headers.
        if (request.getHeaders().getFirstValue("Accept") == null) {
            nettyRequest.setHeader(HttpHeaders.Names.ACCEPT, "*/*");
        }

        if (config.getUserAgent() != null) {
            nettyRequest.setHeader("User-Agent", config.getUserAgent());
        }

        if (request.getCookies() != null && !request.getCookies().isEmpty()) {
            CookieEncoder httpCookieEncoder = new CookieEncoder(false);
            Iterator<Cookie> ic = request.getCookies().iterator();
            Cookie c;
            org.jboss.netty.handler.codec.http.Cookie cookie;
            while (ic.hasNext()) {
                c = ic.next();
                cookie = new DefaultCookie(c.getName(), c.getValue());
                cookie.setPath(c.getPath());
                cookie.setMaxAge(c.getMaxAge());
                cookie.setDomain(c.getDomain());
                httpCookieEncoder.addCookie(cookie);
            }
            nettyRequest.setHeader(HttpHeaders.Names.COOKIE, httpCookieEncoder.encode());
        }

        String reqType = request.getReqType();
        if ("POST".equals(reqType) || "PUT".equals(reqType)) {
            nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, "0");
            if (request.getByteData() != null) {
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getByteData().length));
                nettyRequest.setContent(ChannelBuffers.copiedBuffer(request.getByteData()));
            } else if (request.getStringData() != null) {
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getStringData().length()));
                nettyRequest.setContent(ChannelBuffers.copiedBuffer(request.getStringData(), "UTF-8"));
            } else if (request.getStreamData() != null) {
                int[] lengthWrapper = new int[1];
                byte[] bytes = readFully(request.getStreamData(), lengthWrapper);
                int length = lengthWrapper[0];
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(length));
                nettyRequest.setContent(ChannelBuffers.copiedBuffer(bytes, 0, length));
            } else if (request.getParams() != null) {
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
                nettyRequest.setContent(ChannelBuffers.copiedBuffer(sb.toString().getBytes("UTF-8")));

                if (!request.getHeaders().containsKey(HttpHeaders.Names.CONTENT_TYPE)) {
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/x-www-form-urlencoded");
                }

            } else if (request.getParts() != null) {
                int lenght = computeAndSetContentLength(request, nettyRequest);

                if (lenght == -1) {
                    lenght = MAX_BUFFERED_BYTES;
                }

                MultipartRequestEntity mre = createMultipartRequestEntity(request.getParts(), request.getParams());

                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_TYPE, mre.getContentType());
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(mre.getContentLength()));

                ChannelBuffer b = ChannelBuffers.dynamicBuffer(lenght);
                mre.writeRequest(new ChannelBufferOutputStream(b));
                nettyRequest.setContent(b);
            } else if (request.getEntityWriter() != null) {
                int lenght = computeAndSetContentLength(request, nettyRequest);

                if (lenght == -1) {
                    lenght = MAX_BUFFERED_BYTES;
                }

                ChannelBuffer b = ChannelBuffers.dynamicBuffer(lenght);
                request.getEntityWriter().writeEntity(new ChannelBufferOutputStream(b));
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, b.writerIndex());
                nettyRequest.setContent(b);
            } else if (request.getFile() != null) {
                File file = request.getFile();
                if (file.isHidden() || !file.exists() || !file.isFile()) {
                    throw new IOException(String.format("[" + Thread.currentThread().getName() + "] File %s is not a file, is hidden or doesn't exist", file.getAbsolutePath()));
                }
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, new RandomAccessFile(file, "r").length());
            }
        }
        return nettyRequest;
    }

    public void close() {
        isClose.set(true);
        connectionsPool.destroy();
        openChannels.close();
        this.releaseExternalResources();
        config.reaper().shutdown();
        config.executorService().shutdown();
        socketChannelFactory.releaseExternalResources();
        plainBootstrap.releaseExternalResources();
        secureBootstrap.releaseExternalResources();
    }

    /* @Override */

    public Response prepareResponse(final HttpResponseStatus status,
                                    final HttpResponseHeaders headers,
                                    final Collection<HttpResponseBodyPart> bodyParts) {
        return new NettyAsyncResponse(status, headers, bodyParts);
    }

    /* @Override */

    public <T> Future<T> execute(final Request request, final AsyncHandler<T> asyncHandler) throws IOException {
        return doConnect(request, asyncHandler, null);
    }

    private <T> void execute(final Request request, final NettyResponseFuture<T> f) throws IOException {
        doConnect(request, f.getAsyncHandler(), f);
    }

    private <T> Future<T> doConnect(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> f) throws IOException {

        if (isClose.get()) {
            throw new IOException("Closed");
        }

        URI uri = createUri(request.getUrl());
        Channel channel = lookupInCache(uri);

        if (channel != null && channel.isOpen()) {
            if (channel.isConnected()) {
                HttpRequest nettyRequest = buildRequest(config, request, uri, false);
                
                if (f == null) {
                    f = new NettyResponseFuture<T>(uri, request, asyncHandler, nettyRequest,
                                                   requestTimeout(config, request.getPerRequestConfig()), this);
                } else {
                    f.setNettyRequest(nettyRequest);
                }
                f.setState(NettyResponseFuture.STATE.POOLED);
                
                try {
                    executeRequest(channel, config, f, nettyRequest);
                    return f;
                } catch (ConnectException ex) {
                    // The connection failed because the channel got remotly closed
                    // Let continue the normal processing.
                    connectionsPool.removeAllConnections(channel);
                }
            } else {
                connectionsPool.removeAllConnections(channel);
            }
        }

        if (!connectionsPool.canCacheConnection()) {
            throw new IOException("Too many connections");
        }

        ConnectListener<T> c = new ConnectListener.Builder<T>(config, request, asyncHandler, f, this).build();
        ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer() ;

        boolean useSSl = uri.getScheme().compareToIgnoreCase("https") == 0
                && (proxyServer == null
                || !proxyServer.getProtocolAsString().equals("https"));
        
        configure(c);

        ChannelFuture channelFuture;
        ClientBootstrap bootstrap = useSSl ? secureBootstrap : plainBootstrap;
        try {
            if (proxyServer == null) {
                channelFuture = bootstrap.connect(new InetSocketAddress(uri.getHost(), getPort(uri)));
            } else {               
                channelFuture = bootstrap.connect(new InetSocketAddress(proxyServer.getHost(), proxyServer.getPort()));
            }
            bootstrap.setOption("connectTimeout", config.getConnectionTimeoutInMs());
        } catch (Throwable t) {
            log.error(t);
            c.future().abort(t.getCause());
            return c.future();
        }
        channelFuture.addListener(c);
        openChannels.add(channelFuture.getChannel());
        return c.future();
    }

    protected static int requestTimeout(AsyncHttpClientConfig config, PerRequestConfig perRequestConfig) {
        int result;
        if (perRequestConfig != null) {
            int prRequestTimeout = perRequestConfig.getRequestTimeoutInMs();
            result = (prRequestTimeout != 0 ? prRequestTimeout : config.getRequestTimeoutInMs());
        } else {
            result = config.getRequestTimeoutInMs();
        }
        return result;
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleState state, long lastActivityTimeMillis) throws Exception {
        NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();

        connectionsPool.removeAllConnections(ctx.getChannel());
        future.abort(new IOException("No response received. Connection timed out after " + config.getIdleConnectionTimeoutInMs()));
        closeChannel(ctx);
    }

    private void closeChannel(ChannelHandlerContext ctx) {
        ctx.setAttachment(new DiscardEvent());
        ctx.getChannel().close();
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        // Discard in memory bytes if the HttpContent.interrupt() has been invoked.
        if (ctx.getAttachment() instanceof DiscardEvent) {
            ctx.getChannel().setReadable(false);
            return;
        } else if (!(ctx.getAttachment() instanceof NettyResponseFuture<?>)) {
            // The IdleStateHandler times out and he is calling us.
            // We already closed the channel in IdleStateHandler#channelIdle
            // so we have nothing to do
            return;
        }
        final NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();
        future.touch();

        HttpRequest nettyRequest = future.getNettyRequest();
        AsyncHandler<?> handler = future.getAsyncHandler();

        try {
            if (e.getMessage() instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) e.getMessage();
                // Required if there is some trailing headers.
                future.setHttpResponse(response);
                int statusCode = response.getStatus().getCode();

                String ka = response.getHeader(HttpHeaders.Names.CONNECTION);
                future.setKeepAlive(ka == null || ka.toLowerCase().equals("keep-alive"));

                String wwwAuth = response.getHeader(HttpHeaders.Names.WWW_AUTHENTICATE);
                Request request = future.getRequest();
                if (statusCode == 401
                        && wwwAuth != null
                        && future.getRequest().getRealm() != null
                        && !future.getAndSetAuth(true)) {

                    Realm realm = new Realm.RealmBuilder().clone(request.getRealm())
                            .parseWWWAuthenticateHeader(wwwAuth)
                            .setUri(URI.create(request.getUrl()).getPath())
                            .setMethodName(request.getReqType())
                            .setScheme(request.getRealm().getAuthScheme())
                            .setUsePreemptiveAuth(true)
                            .build();

                    if (log.isDebugEnabled()) {
                        log.debug(String.format("[" + Thread.currentThread().getName() + "] Sending authentication to %s", request.getUrl()));
                    }

                    //Cache our current connection so we don't have to re-open it.
                    markAsDoneAndCacheConnection(future, ctx, false);
                    RequestBuilder builder = new RequestBuilder(future.getRequest());

                    execute(builder.setRealm(realm).build(), future);
                    return;
                }

                String proxyAuth = response.getHeader(HttpHeaders.Names.PROXY_AUTHENTICATE);
                if (statusCode == 407
                        && proxyAuth != null
                        && future.getRequest().getRealm() != null
                        && !future.getAndSetAuth(true)) {

                    if (log.isDebugEnabled()) {
                        log.debug(String.format("[" + Thread.currentThread().getName() + "] Sending proxy authentication to %s", request.getUrl()));
                    }

                    //Cache our current connection so we don't have to re-open it.
                    markAsDoneAndCacheConnection(future, ctx, false);
                    execute(future.getRequest(), future);
                    return;
                }

                if (future.getNettyRequest().getMethod().equals(HttpMethod.CONNECT)
                        && statusCode == 200) {

                    if (log.isDebugEnabled()) {
                        log.debug(String.format("[" + Thread.currentThread().getName() + "] Connected to %s", request.getUrl()));
                    }

                    //Cache our current connection so we don't have to re-open it.
                    markAsDoneAndCacheConnection(future, ctx, false);
                    RequestBuilder builder = new RequestBuilder(future.getRequest());
                    try {
                        upgradeProtocol(ctx.getChannel().getPipeline(), (request.getUrl()));
                    } catch (Throwable ex) {
                        future.abort(ex);
                    }

                    execute(builder.build(), future);
                    return;
                }

                boolean redirectEnabled = (request.isRedirectEnabled() || config.isRedirectEnabled());
                if (redirectEnabled && (statusCode == 302 || statusCode == 301)) {

                    if (future.incrementAndGetCurrentRedirectCount() < config.getMaxRedirects()) {

                        String location = response.getHeader(HttpHeaders.Names.LOCATION);
                        if (location.startsWith("/")) {
                            location = getBaseUrl(future.getURI()) + location;
                        }

                        if (!location.equals(future.getURI().toString())) {
                            URI uri = createUri(location);

                            if (location.startsWith("https")) {
                                upgradeProtocol(ctx.getChannel().getPipeline(), "https");
                            }
                            RequestBuilder builder = new RequestBuilder(future.getRequest());
                            future.setURI(uri);

                            markChannelNotReadable(ctx);
                            String newUrl = uri.toString();

                            if (log.isDebugEnabled()) {
                                log.debug(String.format("[" + Thread.currentThread().getName() + "] Redirecting to %s", newUrl));
                            }
                            execute(builder.setUrl(newUrl).build(), future);
                            return;
                        }
                    } else {
                        throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
                    }
                }

                if (log.isDebugEnabled()) {
                    log.debug(String.format("[" + Thread.currentThread().getName() + "] Status: %s", response.getStatus()));
                    log.debug(String.format("[" + Thread.currentThread().getName() + "] Version: %s", response.getProtocolVersion()));
                    log.debug("\"");
                    if (!response.getHeaderNames().isEmpty()) {
                        for (String name : response.getHeaderNames()) {
                            log.debug(String.format("[" + Thread.currentThread().getName() + "] Header: %s = %s", name, response.getHeaders(name)));
                        }
                        log.debug("\"");
                    }
                }

                if (!future.getAndSetStatusReceived(true) && updateStatusAndInterrupt(handler, new ResponseStatus(future.getURI(), response, this))) {
                    finishUpdate(future, ctx);
                    return;
                } else if (updateHeadersAndInterrupt(handler, new ResponseHeaders(future.getURI(), response, this))) {
                    finishUpdate(future, ctx);
                    return;
                } else if (!response.isChunked()) {
                    if (response.getContent().readableBytes() != 0) {
                        updateBodyAndInterrupt(handler, new ResponseBodyPart(future.getURI(), response, this));
                    }
                    finishUpdate(future, ctx);
                    return;
                }

                if (nettyRequest.getMethod().equals(HttpMethod.HEAD)) {
                    markAsDoneAndCacheConnection(future, ctx, true);
                }

            } else if (e.getMessage() instanceof HttpChunk) {
                HttpChunk chunk = (HttpChunk) e.getMessage();

                if (handler != null) {
                    if (chunk.isLast() || updateBodyAndInterrupt(handler, new ResponseBodyPart(future.getURI(), null, this, chunk))) {
                        if (chunk instanceof DefaultHttpChunkTrailer) {
                            updateHeadersAndInterrupt(handler, new ResponseHeaders(future.getURI(),
                                    future.getHttpResponse(), this, (HttpChunkTrailer) chunk));
                        }
                        finishUpdate(future, ctx);
                    }
                }
            }
        } catch (Exception t) {
            try {
                future.abort(t);
            } finally {
                finishUpdate(future, ctx);
                throw t;
            }
        }
    }

    private void upgradeProtocol(ChannelPipeline p, String scheme) throws IOException, GeneralSecurityException {
        if (p.get(HTTP_HANDLER) != null) {
            p.remove(HTTP_HANDLER);
        }

        if (scheme.startsWith("https")) {
            if (p.get(SSL_HANDLER) == null) {
                p.addFirst(HTTP_HANDLER, new HttpClientCodec());
                p.addFirst(SSL_HANDLER, new SslHandler(createSSLEngine()));
            } else {
                p.addAfter(SSL_HANDLER, HTTP_HANDLER, new HttpClientCodec());
            }

        } else {
            p.addFirst(HTTP_HANDLER, new HttpClientCodec());
        }
    }

    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {

        if (log.isDebugEnabled()) {
            log.debug(String.format("[" + Thread.currentThread().getName() + "] Channel state: %s", e.getState()));
        }

        connectionsPool.removeAllConnections(ctx.getChannel());

        if (!isClose.get() && ctx.getAttachment() instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();

            // Cleaning a broken connection.
            if (Boolean.class.isAssignableFrom(e.getValue().getClass()) && !Boolean.class.cast(e.getValue())) {

                if (remotelyClosed(ctx.getChannel(), future)) {
                    return;
                }
            }

            if (future != null && !future.isDone() && !future.isCancelled()) {
                try {
                    future.getAsyncHandler().onThrowable(new IOException("No response received. Connection timed out"));
                } catch (Throwable t) {
                    log.error(t);
                }
            }
        }
        super.channelClosed(ctx,e);
    }

    private static boolean remotelyClosed(Channel channel, NettyResponseFuture<?> future) {
        if (future == null || future.getState() == NettyResponseFuture.STATE.POOLED) {
            if (NettyResponseFuture.class.isAssignableFrom(
                    channel.getPipeline().getContext(NettyAsyncHttpProvider.class).getAttachment().getClass())) {

                NettyResponseFuture<?> f = (NettyResponseFuture<?>)
                        channel.getPipeline().getContext(NettyAsyncHttpProvider.class).getAttachment();
                f.setState(NettyResponseFuture.STATE.RECONNECTED);
                try {
                    f.provider().execute(f.getRequest(),f);
                    return true;
                } catch (IOException iox) {
                    f.setState(NettyResponseFuture.STATE.CLOSED);
                    f.abort(iox);
                    log.error(iox);
                }
            }
        }

        if (future.getState() != NettyResponseFuture.STATE.NEW) {
            return true;
        }

        return false;
    }


    private void markAsDoneAndCacheConnection(final NettyResponseFuture<?> future, final ChannelHandlerContext ctx, boolean releaseFuture) throws MalformedURLException {
        if (future.getKeepAlive()) {
            connectionsPool.addConnection(getBaseUrl(future.getURI()), ctx.getChannel());
        }

        if (releaseFuture) {
            future.done();

            if (!future.getKeepAlive()) {
                closeChannel(ctx);
            }
        }
    }

    private String getBaseUrl(URI uri) {
        String url = uri.getScheme() + "://" + uri.getAuthority();
        int port = uri.getPort();
        if (port == -1) {
            port = getPort(uri);
            url += ":" + port;
        }
        return url;
    }

    private static int getPort(URI uri) {
        int port = uri.getPort();
        if (port == -1)
            port = uri.getScheme().equals("http") ? 80 : 443;
        return port;
    }

    private void finishUpdate(NettyResponseFuture<?> future, ChannelHandlerContext ctx) throws IOException {
        markChannelNotReadable(ctx);
        markAsDoneAndCacheConnection(future, ctx, true);
    }

    private static void markChannelNotReadable(ChannelHandlerContext ctx) {
        // Catch any unexpected exception when marking the channel.        
        ctx.setAttachment(new DiscardEvent());
        try {
            ctx.getChannel().setReadable(false);
        } catch (Exception ex) {
            log.debug(ex);
        }
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
    private final boolean updateBodyAndInterrupt(AsyncHandler handler, HttpResponseBodyPart c) throws Exception {
        return handler.onBodyPartReceived(c) != STATE.CONTINUE;
    }

    //Simple marker for stopping publishing bytes.
    private final static class DiscardEvent {
    }

    //Simple marker for closed events
    private final static class ClosedEvent {
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
            throws Exception {
        Channel channel = e.getChannel();
        Throwable cause = e.getCause();

        if (log.isDebugEnabled()) {
            log.debug("Fatal I/O exception: ", cause);
        }

        if (ctx.getAttachment() instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();

            if (cause != null && ClosedChannelException.class.isAssignableFrom(cause.getClass())) {
                // We will recover from a badly cached connection.
                return;
            }

            // Windows only.
            if (cause != null && cause.getMessage() != null
                    && cause.getMessage().equals("An established connection was aborted by the software in your host machine")) {
                if (log.isDebugEnabled()) {
                    log.debug(cause);
                }

                remotelyClosed(channel, null);
                return;
            }

            if (future != null) {
                try {
                    future.abort(cause);
                } catch (Throwable t) {
                    log.error(t);
                }
            }
        }
    }

    private final static int computeAndSetContentLength(Request request, HttpRequest r) {
        int lenght = (int) request.getLength();
        if (lenght == -1 && r.getHeader(HttpHeaders.Names.CONTENT_LENGTH) != null) {
            lenght = Integer.valueOf(r.getHeader(HttpHeaders.Names.CONTENT_LENGTH));
        }

        if (lenght != -1) {
            r.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(lenght));
        }
        return lenght;
    }

    /**
     * This is quite ugly as our internal names are duplicated, but we build on top of HTTP Client implementation.
     *
     * @param params
     * @param methodParams
     * @return
     * @throws java.io.FileNotFoundException
     */
    private final static MultipartRequestEntity createMultipartRequestEntity(List<Part> params, FluentStringsMap methodParams) throws FileNotFoundException {
        com.ning.http.multipart.Part[] parts = new com.ning.http.multipart.Part[params.size()];
        int i = 0;

        for (Part part : params) {
            if (part instanceof StringPart) {
                parts[i] = new com.ning.http.multipart.StringPart(part.getName(),
                        ((StringPart) part).getValue(),
                        "UTF-8");
            } else if (part instanceof FilePart) {
                parts[i] = new com.ning.http.multipart.FilePart(part.getName(),
                        ((FilePart) part).getFile(),
                        ((FilePart) part).getMimeType(),
                        ((FilePart) part).getCharSet());

            } else if (part instanceof ByteArrayPart) {
                PartSource source = new ByteArrayPartSource(((ByteArrayPart) part).getFileName(), ((ByteArrayPart) part).getData());
                parts[i] = new com.ning.http.multipart.FilePart(part.getName(),
                        source,
                        ((ByteArrayPart) part).getMimeType(),
                        ((ByteArrayPart) part).getCharSet());

            } else if (part == null) {
                throw new NullPointerException("Part cannot be null");
            } else {
                throw new IllegalArgumentException(String.format("[" + Thread.currentThread().getName() + "] Unsupported part type for multipart parameter %s",
                        part.getName()));
            }
            ++i;
        }
        return new MultipartRequestEntity(parts, methodParams);
    }

    // TODO: optimize; better use segmented-buffer to avoid reallocs (expand-by-doubling)

    private static byte[] readFully(InputStream in, int[] lengthWrapper) throws IOException {
        // just in case available() returns bogus (or -1), allocate non-trivial chunk
        byte[] b = new byte[Math.max(512, in.available())];
        int offset = 0;
        while (true) {
            int left = b.length - offset;
            int count = in.read(b, offset, left);
            if (count < 0) { // EOF
                break;
            }
            offset += count;
            if (count == left) { // full buffer, need to expand
                b = doubleUp(b);
            }
        }
        // wish Java had Tuple return type...
        lengthWrapper[0] = offset;
        return b;
    }

    private static byte[] doubleUp(byte[] b) {
        // TODO: in Java 1.6, we would use Arrays.copyOf(), but for now we only rely on 1.5:
        int len = b.length;
        byte[] b2 = new byte[len + len];
        System.arraycopy(b, 0, b2, 0, len);
        return b2;
    }

    private static class ProgressListener implements ChannelFutureProgressListener {

        private final boolean notifyHeaders;
        private final AsyncHandler asyncHandler;

        public ProgressListener(boolean notifyHeaders, AsyncHandler asyncHandler) {
            this.notifyHeaders = notifyHeaders;
            this.asyncHandler = asyncHandler;
        }

        public void operationComplete(ChannelFuture cf) {
            // The write operation failed. If the channel was cached, it means it got asynchronously closed.
            // Let's retry a second time.
            Throwable cause = cf.getCause();
            if (cause != null && ClosedChannelException.class.isAssignableFrom(cause.getClass())) {
                if (log.isDebugEnabled()) {
                    log.debug(cf.getCause());
                }
                
                remotelyClosed(cf.getChannel(), null);
                return;
            }

            if (ProgressAsyncHandler.class.isAssignableFrom(asyncHandler.getClass())) {
                if (notifyHeaders) {
                    ProgressAsyncHandler.class.cast(asyncHandler).onHeaderWriteCompleted();
                } else {
                    ProgressAsyncHandler.class.cast(asyncHandler).onContentWriteCompleted();
                }
            }
        }

        public void operationProgressed(ChannelFuture cf, long amount, long current, long total) {
            if (ProgressAsyncHandler.class.isAssignableFrom(asyncHandler.getClass())) {
                ProgressAsyncHandler.class.cast(asyncHandler).onContentWriteProgess(amount, current, total);
            }
        }
    }
}
