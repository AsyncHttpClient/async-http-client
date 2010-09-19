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
package com.ning.http.client.providers;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHandler.STATE;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.ByteArrayPart;
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
import com.ning.http.client.RequestType;
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
import org.jboss.netty.channel.ChannelFutureListener;
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
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.jboss.netty.channel.Channels.pipeline;

public class NettyAsyncHttpProvider extends IdleStateHandler implements AsyncHttpProvider<HttpResponse> {
    private final static String HTTP_HANDLER = "httpHandler";
    private final static String SSL_HANDLER = "sslHandler";

    private final static Logger log = LogManager.getLogger(NettyAsyncHttpProvider.class);

    private final ClientBootstrap bootstrap;

    private final static int MAX_BUFFERED_BYTES = 8192;

    private final AsyncHttpClientConfig config;

    private final ConcurrentHashMap<String, Channel> connectionsPool = new ConcurrentHashMap<String, Channel>();

    private final AtomicInteger activeConnectionsCount = new AtomicInteger();

    private final ConcurrentHashMap<String, AtomicInteger> connectionsPerHost = new ConcurrentHashMap<String, AtomicInteger>();

    private final AtomicBoolean isClose = new AtomicBoolean(false);

    private final NioClientSocketChannelFactory socketChannelFactory;

    private final ChannelGroup openChannels = new DefaultChannelGroup("asyncHttpClient");

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {
        super(new HashedWheelTimer(), 0, 0, config.getIdleConnectionTimeoutInMs(), TimeUnit.MILLISECONDS);
        socketChannelFactory = new NioClientSocketChannelFactory(
                Executors.newCachedThreadPool(),
                config.executorService());
        bootstrap = new ClientBootstrap(socketChannelFactory);
        this.config = config;
    }

    void configure(final boolean useSSL, final ConnectListener<?> cl) {

        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            /* @Override */
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();

                if (useSSL) {
                    try {
                        SSLEngine sslEngine = config.getSSLEngine();
                        if (sslEngine == null) {
                            sslEngine = SslUtils.getSSLEngine();
                        }
                        pipeline.addLast(SSL_HANDLER, new SslHandler(sslEngine));
                    } catch (Throwable ex) {
                        cl.future().abort(ex);
                    }
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
        Channel channel = connectionsPool.remove(getBaseUrl(uri));
        if (channel != null) {
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
        }
        return channel;
    }

    /**
     * Non Blocking connect.
     */
    private final static class ConnectListener<T> implements ChannelFutureListener {

        private final AsyncHttpClientConfig config;
        private final NettyResponseFuture<T> future;
        private final HttpRequest nettyRequest;

        private ConnectListener(AsyncHttpClientConfig config,
                                NettyResponseFuture<T> future,
                                HttpRequest nettyRequest) {
            this.config = config;
            this.future = future;
            this.nettyRequest = nettyRequest;
        }

        public NettyResponseFuture<T> future() {
            return future;
        }

        public final void operationComplete(ChannelFuture f) throws Exception {
            try {
                executeRequest(f.getChannel(), config, future, nettyRequest);
            } catch (ConnectException ex) {
                future.abort(ex);
            }
        }

        public static class Builder<T> {
            private final Logger log = LogManager.getLogger(Builder.class);
            private final AsyncHttpClientConfig config;
            private final Request request;
            private final AsyncHandler<T> asyncHandler;
            private NettyResponseFuture<T> future;

            public Builder(AsyncHttpClientConfig config, Request request, AsyncHandler<T> asyncHandler) {
                this.config = config;
                this.request = request;
                this.asyncHandler = asyncHandler;
                this.future = null;
            }

            public Builder(AsyncHttpClientConfig config, Request request, AsyncHandler<T> asyncHandler, NettyResponseFuture<T> future) {
                this.config = config;
                this.request = request;
                this.asyncHandler = asyncHandler;
                this.future = future;
            }

            public ConnectListener<T> build() throws IOException {

                URI uri = createUri(request.getRawUrl());
                HttpRequest nettyRequest = buildRequest(config, request, uri, true);

                log.debug("Executing the doConnect operation: %s", asyncHandler);

                if (future == null) {
                    future = new NettyResponseFuture<T>(uri, request, asyncHandler,
                            nettyRequest, requestTimeout(config, request.getPerRequestConfig()));
                }
                return new ConnectListener<T>(config, future, nettyRequest);
            }
        }
    }

    private final static <T> void executeRequest(final Channel channel,
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
            throw new ConnectException(String.format("Connection refused to %s", url));
        }

        channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(future);

        /**
         * Currently it is impossible to write the headers and the FIle using a single I/O operation.
         * I've filled NETTY-XXX as an enhancement.
         */
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
            future.setReaperFuture(config.reaper().scheduleAtFixedRate(new Runnable() {
                public void run() {
                    if (future.hasExpired()) {
                        if (log.isDebugEnabled()) {
                            log.debug("Request Timeout expired for " + future);
                        }
                        future.abort(new TimeoutException("Request timed out."));
                        closeChannel(channel.getPipeline().getContext(NettyAsyncHttpProvider.class));
                    }
                }
            }, 0, requestTimeout(config, future.getRequest().getPerRequestConfig()), TimeUnit.MILLISECONDS));
        } catch (RejectedExecutionException ex) {
            future.abort(ex);
        }
    }

    private final static HttpRequest buildRequest(AsyncHttpClientConfig config, Request request, URI uri, boolean allowConnect) throws IOException {

        String method = request.getType().toString();
        if (allowConnect && ((request.getProxyServer() != null || config.getProxyServer() != null) && "https".equalsIgnoreCase(uri.getScheme()))) {
            method = HttpMethod.CONNECT.toString();
        }
        return construct(config, request, new HttpMethod(method), uri);
    }

    private final static URI createUri(String u) {
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
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, m, uri.getPath());
        } else {
            StringBuilder path = new StringBuilder(uri.getRawPath());
            if (uri.getQuery() != null) {
                path.append("?").append(uri.getRawQuery());
            }
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, m, path.toString());
        }
        nettyRequest.setHeader(HttpHeaders.Names.HOST, host + ":" + getPort(uri));

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
        if (realm != null) {
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
                    throw new IllegalStateException(String.format("Invalid Authentication %s", realm.toString()));
            }
        }

        String ka = config.getKeepAlive() ? "keep-alive" : "close";
        nettyRequest.setHeader(HttpHeaders.Names.CONNECTION, ka);
        ProxyServer proxyServer = config.getProxyServer() != null ? config.getProxyServer() : request.getProxyServer();
        if (proxyServer != null) {
            nettyRequest.setHeader("Proxy-Connection", ka);
            if (proxyServer.getPrincipal() != null) {
                nettyRequest.setHeader(HttpHeaders.Names.PROXY_AUTHORIZATION, AuthenticatorUtils.computeBasicAuthentication(proxyServer));
            }
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

        RequestType type = request.getType();
        if (RequestType.POST.equals(type) || RequestType.PUT.equals(type)) {
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
                    throw new IOException(String.format("File %s is not a file, is hidden or doesn't exist", file.getAbsolutePath()));
                }
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, new RandomAccessFile(file, "r").length());
            }
        }
        return nettyRequest;
    }

    public void close() {
        isClose.set(true);
        connectionsPool.clear();
        openChannels.close();
        this.releaseExternalResources();
        config.reaper().shutdown();
        config.executorService().shutdown();
        socketChannelFactory.releaseExternalResources();
        bootstrap.releaseExternalResources();
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

        if (config.getMaxTotalConnections() != -1 && activeConnectionsCount.getAndIncrement() >= config.getMaxTotalConnections()) {
            activeConnectionsCount.decrementAndGet();
            throw new IOException("Too many connections");
        }

        URI uri = createUri(request.getUrl());

        log.debug("Lookup cache: %s", uri);

        Channel channel = lookupInCache(uri);
        if (channel != null && channel.isOpen()) {
            if (channel.isConnected()) {
                // Decrement the count as this is not a new connection.
                if (config.getMaxConnectionPerHost() != -1) {
                    activeConnectionsCount.decrementAndGet();
                }

                HttpRequest nettyRequest = buildRequest(config, request, uri, false);
                if (f == null) {
                    f = new NettyResponseFuture<T>(uri, request, asyncHandler, nettyRequest, requestTimeout(config, request.getPerRequestConfig()));
                } else {
                    f.setNettyRequest(nettyRequest);
                }

                try {
                    executeRequest(channel, config, f, nettyRequest);
                    return f;
                } catch (ConnectException ex) {
                    // The connection failed because the channel got remotly closed
                    // Let continue the normal processing.
                    connectionsPool.remove(channel);
                }
            } else {
                connectionsPool.remove(channel);
            }
        }
        ConnectListener<T> c = new ConnectListener.Builder<T>(config, request, asyncHandler, f).build();


        boolean useSSl = uri.getScheme().compareToIgnoreCase("https") == 0
                && (request.getProxyServer() == null
                || !request.getProxyServer().getProtocolAsString().equals("https"));
        configure(useSSl, c);

        ChannelFuture channelFuture;
        try {
            if (config.getProxyServer() == null && request.getProxyServer() == null) {
                channelFuture = bootstrap.connect(new InetSocketAddress(uri.getHost(), getPort(uri)));
            } else {
                ProxyServer proxy = (request.getProxyServer() == null ? config.getProxyServer() : request.getProxyServer());
                channelFuture = bootstrap.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()));
            }
            bootstrap.setOption("connectTimeout", config.getConnectionTimeoutInMs());
        } catch (Throwable t) {
            if (config.getMaxTotalConnections() != -1) {
                activeConnectionsCount.decrementAndGet();
            }
            log.error(t);
            c.future().abort(t.getCause());
            return c.future();
        }
        channelFuture.addListener(c);
        openChannels.add(channelFuture.getChannel());
        return c.future();
    }

    private static int requestTimeout(AsyncHttpClientConfig config, PerRequestConfig perRequestConfig) {
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
        closeChannel(ctx);

        for (Entry<String, Channel> e : connectionsPool.entrySet()) {
            if (e.getValue().equals(ctx.getChannel())) {
                connectionsPool.remove(e.getKey());
                if (config.getMaxTotalConnections() != -1) {
                    activeConnectionsCount.decrementAndGet();
                }
                break;
            }
        }
        future.abort(new IOException("No response received. Connection timed out after " + config.getIdleConnectionTimeoutInMs()));
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
                            .setMethodName(request.getType().toString())
                            .setScheme(Realm.AuthScheme.DIGEST)
                            .build();

                    log.debug("Sending authentication to %s", request.getUrl());

                    //Cache our current connection so we don't have to re-open it.
                    markAsDoneAndCacheConnection(future, ctx.getChannel(), false);
                    RequestBuilder builder = new RequestBuilder(future.getRequest());

                    execute(builder.setRealm(realm).build(), future);
                    return;
                }

                String proxyAuth = response.getHeader(HttpHeaders.Names.PROXY_AUTHENTICATE);
                if (statusCode == 407
                        && proxyAuth != null
                        && future.getRequest().getRealm() != null
                        && !future.getAndSetAuth(true)) {

                    log.debug("Sending proxy authentication to %s", request.getUrl());

                    //Cache our current connection so we don't have to re-open it.
                    markAsDoneAndCacheConnection(future, ctx.getChannel(), false);
                    execute(future.getRequest(), future);
                    return;
                }

                if (future.getNettyRequest().getMethod().equals(HttpMethod.CONNECT)
                        && statusCode == 200) {

                    log.debug("Connected to %s", request.getUrl());

                    //Cache our current connection so we don't have to re-open it.
                    markAsDoneAndCacheConnection(future, ctx.getChannel(), false);
                    RequestBuilder builder = new RequestBuilder(future.getRequest());
                    try {
                        ChannelPipeline p = ctx.getChannel().getPipeline();
                        if (p.get(HTTP_HANDLER) != null) {
                            p.remove(HTTP_HANDLER);
                        }

                        if (request.getUrl().startsWith("https")) {

                            SSLEngine sslEngine = config.getSSLEngine();
                            if (sslEngine == null) {
                                sslEngine = SslUtils.getSSLEngine();
                            }

                            if (p.get(SSL_HANDLER) == null) {
                                p.addFirst(HTTP_HANDLER, new HttpClientCodec());
                                p.addFirst(SSL_HANDLER, new SslHandler(sslEngine));
                            } else {
                                p.addAfter(SSL_HANDLER, HTTP_HANDLER, new HttpClientCodec());
                            }

                        } else {
                            p.addFirst(HTTP_HANDLER, new HttpClientCodec());
                        }

                    } catch (Throwable ex) {
                        future.abort(ex);
                    }

                    execute(builder.build(), future);
                    return;
                }

                if (config.isRedirectEnabled()
                        && (statusCode == 302 || statusCode == 301)) {

                    if (future.incrementAndGetCurrentRedirectCount() < config.getMaxRedirects()) {

                        String location = response.getHeader(HttpHeaders.Names.LOCATION);
                        if (location.startsWith("/")) {
                            location = getBaseUrl(future.getURI()) + location;
                        }

                        URI uri = createUri(location);
                        RequestBuilder builder = new RequestBuilder(future.getRequest());
                        future.setURI(uri);

                        closeChannel(ctx);
                        String newUrl = uri.toString();

                        log.debug("Redirecting to %s", newUrl);

                        execute(builder.setUrl(newUrl).build(), future);
                        return;
                    } else {
                        throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
                    }
                }

                if (log.isDebugEnabled()) {
                    log.debug("Status: %s", response.getStatus());
                    log.debug("Version: %s", response.getProtocolVersion());
                    log.debug("\"");
                    if (!response.getHeaderNames().isEmpty()) {
                        for (String name : response.getHeaderNames()) {
                            log.debug("Header: %s = %s", name, response.getHeaders(name));
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
                    markAsDoneAndCacheConnection(future, ctx.getChannel(), true);
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

    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {

        log.debug("Channel closed: %s", e.getState());

        if (!isClose.get() && ctx.getAttachment() instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();

            if (future != null && !future.isDone() && !future.isCancelled()) {
                try {
                    future.getAsyncHandler().onThrowable(new IOException("No response received. Connection timed out"));
                } catch (Throwable t) {
                    log.error(t);
                }
            }
            connectionsPool.remove(ctx.getChannel());
        }
        ctx.sendUpstream(e);
    }

    private void markAsDoneAndCacheConnection(final NettyResponseFuture<?> future, final Channel channel, boolean releaseFuture) throws MalformedURLException {
        if (future.getKeepAlive()) {
            AtomicInteger connectionPerHost = connectionsPerHost.get(getBaseUrl(future.getURI()));
            if (connectionPerHost == null) {
                connectionPerHost = new AtomicInteger(1);
                connectionsPerHost.put(getBaseUrl(future.getURI()), connectionPerHost);
            }

            if (config.getMaxConnectionPerHost() == -1 || connectionPerHost.getAndIncrement() < config.getMaxConnectionPerHost()) {
                connectionsPool.put(getBaseUrl(future.getURI()), channel);
            } else {
                connectionPerHost.decrementAndGet();
                log.warn("Maximum connections per hosts reached " + config.getMaxConnectionPerHost());
            }
        } else if (config.getMaxTotalConnections() != -1) {
            activeConnectionsCount.decrementAndGet();
        }

        if (releaseFuture)
            future.done();
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
        closeChannel(ctx);
        markAsDoneAndCacheConnection(future, ctx.getChannel(), true);
    }

    private static void closeChannel(ChannelHandlerContext ctx) {
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
        Channel ch = e.getChannel();
        Throwable cause = e.getCause();

        if (log.isDebugEnabled())
            log.debug("I/O Exception during read or doConnect: ", cause);
        if (ctx.getAttachment() instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();

            if (future != null) {
                try {
                    future.abort(cause);
                } catch (Throwable t) {
                    log.error(t);
                }
            }
        }

        if (log.isDebugEnabled()) {
            log.debug(e.toString());
            log.debug(ch.toString());
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
                throw new IllegalArgumentException(String.format("Unsupported part type for multipart parameter %s",
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
