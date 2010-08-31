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
import com.ning.http.client.ProxyServer;
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
import com.ning.http.util.SslUtils;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferOutputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
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
import org.jboss.netty.handler.timeout.IdleState;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.internal.ConcurrentHashMap;

import javax.net.ssl.SSLEngine;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.jboss.netty.channel.Channels.pipeline;

public class NettyAsyncHttpProvider extends IdleStateHandler implements AsyncHttpProvider<HttpResponse> {
    private final Logger log = LogManager.getLogger(NettyAsyncHttpProvider.class);
    private final ClientBootstrap bootstrap;
    private final static int MAX_BUFFERRED_BYTES = 8192;

    private final AsyncHttpClientConfig config;

    private final ConcurrentHashMap<String, Channel> connectionsPool = new ConcurrentHashMap<String, Channel>();

    private final AtomicInteger activeConnectionsCount = new AtomicInteger();

    private final ConcurrentHashMap<String, AtomicInteger> connectionsPerHost = new ConcurrentHashMap<String, AtomicInteger>();

    private final AtomicBoolean isClose = new AtomicBoolean(false);

    private final NioClientSocketChannelFactory socketChannelFactory;

    private final ChannelGroup openChannels = new DefaultChannelGroup("asyncHttpClient");

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {
        super(new HashedWheelTimer(), 0, 0, config.getIdleConnectionTimeoutInMs(), TimeUnit.MILLISECONDS) ;
        socketChannelFactory = new NioClientSocketChannelFactory(
                Executors.newCachedThreadPool(),
                config.executorService());
        bootstrap = new ClientBootstrap(socketChannelFactory);
        this.config = config;
    }

    void configure(final boolean useSSL, final ConnectListener<?> cl){

        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            /* @Override */
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();

                if (useSSL){
                    try{
                        SSLEngine sslEngine = config.getSSLEngine();
                        if (sslEngine == null){
                            sslEngine = SslUtils.getSSLEngine();
                        }
                        pipeline.addLast("ssl", new SslHandler(sslEngine));
                    } catch (Throwable ex){
                        cl.future().abort(ex);
                    }
                }
                            
                pipeline.addLast("codec", new HttpClientCodec());

                if (config.isCompressionEnabled()) {
                    pipeline.addLast("inflater", new HttpContentDecompressor());
                }
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
            } catch (ConnectException ex){
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
                HttpRequest nettyRequest = buildRequest(config,request,uri);

                log.debug("Executing the doConnect operation: %s", asyncHandler);

                if (future == null){
                    future = new NettyResponseFuture<T>(uri, request, asyncHandler,
                            nettyRequest, config.getRequestTimeoutInMs());
                }
                return new ConnectListener<T>(config, future, nettyRequest);
            }
        }
    }

    private final static <T> void executeRequest(final Channel channel,
                                                 final AsyncHttpClientConfig config,
                                                 final NettyResponseFuture<T> future,
                                                 final HttpRequest nettyRequest) throws ConnectException {

        if (!channel.isConnected()){
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
        channel.write(nettyRequest);

        try{
            future.setReaperFuture(config.reaper().schedule(new Callable<Object>() {
                public Object call() {
                    if (!future.isDone() && !future.isCancelled()) {
                        future.abort(new TimeoutException());
                        channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(ClosedEvent.class);
                    }
                    return null;
                }

            }, config.getRequestTimeoutInMs(), TimeUnit.MILLISECONDS));
        } catch (RejectedExecutionException ex){
            future.abort(ex);
        }
    }

    private final static HttpRequest buildRequest(AsyncHttpClientConfig config,Request request, URI uri) throws IOException{
        return construct(config, request, new HttpMethod(request.getType().toString()), uri);
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

        StringBuilder path = new StringBuilder(uri.getPath());
        if (uri.getQuery() != null) {
            path.append("?").append(uri.getRawQuery());
        }
        HttpRequest nettyRequest;
        if (config.getProxyServer() != null || request.getProxyServer() != null) {
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, m, uri.toString());
        } else {
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, m, path.toString());
        }
        nettyRequest.setHeader(HttpHeaders.Names.HOST, host + ":" + getPort(uri));

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

        String ka = config.getKeepAlive() ? "keep-alive" : "close";
        nettyRequest.setHeader(HttpHeaders.Names.CONNECTION, ka);
        if (config.getProxyServer() != null || request.getProxyServer() != null) {
            nettyRequest.setHeader("Proxy-Connection", ka);
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

        if (config.isCompressionEnabled()) {
            nettyRequest.setHeader(HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP);
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
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getStreamData().available()));
                byte[] b = new byte[request.getStreamData().available()];
                request.getStreamData().read(b);
                nettyRequest.setContent(ChannelBuffers.copiedBuffer(b));
            } else if (request.getParams() != null) {
                StringBuilder sb = new StringBuilder();
                for (final Entry<String, List<String>> paramEntry : request.getParams()) {
                    for (final String value : paramEntry.getValue()) {
                        sb.append(paramEntry.getKey());
                        sb.append("=");
                        sb.append(value);
                        sb.append("&");
                    }
                }
                sb.deleteCharAt(sb.length() - 1);
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(sb.length()));
                nettyRequest.setContent(ChannelBuffers.copiedBuffer(sb.toString().getBytes()));

                if (!request.getHeaders().containsKey(HttpHeaders.Names.CONTENT_TYPE)) {
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_TYPE,"application/x-www-form-urlencoded");
                }

            } else if (request.getParts() != null) {
                int lenght = computeAndSetContentLength(request, nettyRequest);

                if (lenght == -1) {
                    lenght = MAX_BUFFERRED_BYTES;
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
                    lenght = MAX_BUFFERRED_BYTES;
                }

                ChannelBuffer b = ChannelBuffers.dynamicBuffer(lenght);
                request.getEntityWriter().writeEntity(new ChannelBufferOutputStream(b));
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, b.writerIndex());
                nettyRequest.setContent(b);
            }
        }

        if (nettyRequest.getHeader(HttpHeaders.Names.CONTENT_TYPE) == null) {
            nettyRequest.setHeader(HttpHeaders.Names.CONTENT_TYPE, "text/html; charset=utf-8");
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
        return new NettyAsyncResponse(status,headers,bodyParts);
    }
    
    /* @Override */
    public <T> Future<T> execute(final Request request, final AsyncHandler<T> asyncHandler) throws IOException {
        return doConnect(request,asyncHandler, null);
    }

    private <T> void execute(final Request request, final NettyResponseFuture<T> f) throws IOException {
        doConnect(request,f.getAsyncHandler(),f);
    }

    private <T> Future<T> doConnect(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> f) throws IOException{
        
        if (isClose.get()){
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
            // Decrement the count as this is not a new connection.
            activeConnectionsCount.decrementAndGet();
            
            HttpRequest nettyRequest = buildRequest(config,request,uri);
            if (f == null) {
                f = new NettyResponseFuture<T>(uri, request, asyncHandler,
                                               nettyRequest, config.getRequestTimeoutInMs());
            }
            executeRequest(channel, config,f,nettyRequest);
            return f;
        }
        ConnectListener<T> c = new ConnectListener.Builder<T>(config, request, asyncHandler,f).build();
        configure(uri.getScheme().compareToIgnoreCase("https") == 0, c);

        ChannelFuture channelFuture;
        try{
            if (config.getProxyServer() == null && request.getProxyServer() == null) {
                channelFuture = bootstrap.connect(new InetSocketAddress(uri.getHost(), getPort(uri)));
            } else {
                ProxyServer proxy = (request.getProxyServer() == null ? config.getProxyServer() : request.getProxyServer());
                channelFuture = bootstrap.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()));
            }
            bootstrap.setOption("connectTimeout", config.getConnectionTimeoutInMs());
        } catch (Throwable t){
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

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleState state, long lastActivityTimeMillis) throws Exception {
        NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();
        closeChannel(ctx);
        
        for (Entry<String,Channel> e: connectionsPool.entrySet()) {
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
        } else if ( !(ctx.getAttachment() instanceof NettyResponseFuture<?>))   {
            // The IdleStateHandler times out and he is calling us.
            // We already closed the channel in IdleStateHandler#channelIdle
            // so we have nothing to do
            return;
        }
        NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();
        HttpRequest nettyRequest = future.getNettyRequest();
        AsyncHandler<?> handler = future.getAsyncHandler();
        
        try{
            if (e.getMessage() instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) e.getMessage();
                // Required if there is some trailing headers.
                future.setHttpResponse(response);

                String ka = response.getHeader("Connection");
                future.setKeepAlive(ka == null || ka.toLowerCase().equals("keep-alive"));

                if (config.isRedirectEnabled()
                        && (response.getStatus().getCode() == 302 || response.getStatus().getCode() == 301) ){

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

                        execute(builder.setUrl(newUrl).build(),future);
                        return;
                    } else {
                        throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
                    }
                }

                if (log.isDebugEnabled()){
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

                if (updateStatusAndInterrupt(handler, new ResponseStatus(future.getURI(),response, this))) {
                    finishUpdate(future, ctx);
                    return;
                } else if (updateHeadersAndInterrupt(handler, new ResponseHeaders(future.getURI(),response, this))) {
                    finishUpdate(future, ctx);
                    return;
                } else if (!response.isChunked()) {
                    if (response.getContent().readableBytes() != 0) {
                        updateBodyAndInterrupt(handler, new ResponseBodyPart(future.getURI(),response, this));
                    }
                    finishUpdate(future, ctx);
                    return;
                }

                if (nettyRequest.getMethod().equals(HttpMethod.HEAD)) {
                    markAsDoneAndCacheConnection(future, ctx.getChannel());
                }

            } else if (e.getMessage() instanceof HttpChunk) {
                HttpChunk chunk = (HttpChunk) e.getMessage();

                if (handler != null) {
                    if (chunk.isLast() || updateBodyAndInterrupt(handler, new ResponseBodyPart(future.getURI(),null, this,chunk))) {
                        if (chunk instanceof DefaultHttpChunkTrailer) {
                            updateHeadersAndInterrupt(handler, new ResponseHeaders(future.getURI(),
                                    future.getHttpResponse(), this, (HttpChunkTrailer) chunk));
                        }
                        finishUpdate(future, ctx);
                    }
                }
            }
        } catch (Exception t){
            try {
                future.abort(t);
            } finally {
                finishUpdate(future,ctx);
                throw t;
            }
        }
    }

    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {

        log.debug("Channel closed: %s", e.getState());

        if (!isClose.get() && ctx.getAttachment() instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();

            if (future!= null && !future.isDone() && !future.isCancelled()){
                future.getAsyncHandler().onThrowable(new IOException("No response received. Connection timed out"));
            }
        }
        ctx.sendUpstream(e);
    }

    private void markAsDoneAndCacheConnection(final NettyResponseFuture<?> future, final Channel channel) throws MalformedURLException {
        if (future.getKeepAlive()){
            AtomicInteger connectionPerHost = connectionsPerHost.get(getBaseUrl(future.getURI()));
            if (connectionPerHost == null) {
                connectionPerHost = new AtomicInteger(1);
                connectionsPerHost.put(getBaseUrl(future.getURI()),connectionPerHost);
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

        future.done();
    }

    private String getBaseUrl(URI uri){
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
            port = uri.getScheme().equals("http")? 80: 443 ;
        return port;
    }
    
    private void finishUpdate(NettyResponseFuture<?> future, ChannelHandlerContext ctx) throws IOException {
        closeChannel(ctx);
        markAsDoneAndCacheConnection(future, ctx.getChannel());
    }

    private void closeChannel(ChannelHandlerContext ctx) {
        // Catch any unexpected exception when marking the channel.        
        ctx.setAttachment(new DiscardEvent());
        try{
            ctx.getChannel().setReadable(false);
        } catch (Exception ex){
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

            if (future!= null){
                future.getAsyncHandler().onThrowable(cause);
            }
        }

        if (log.isDebugEnabled()){
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
}
