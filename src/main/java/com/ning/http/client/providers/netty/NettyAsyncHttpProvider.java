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
import com.ning.http.client.Body;
import com.ning.http.client.ConnectionsPool;
import com.ning.http.client.Cookie;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.PerRequestConfig;
import com.ning.http.client.ProgressAsyncHandler;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.RandomAccessBody;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.logging.LogManager;
import com.ning.http.client.logging.Logger;
import com.ning.http.client.providers.jdk.JDKAsyncHttpProvider;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.util.AsyncHttpProviderUtils;
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
import org.jboss.netty.channel.DefaultChannelFuture;
import org.jboss.netty.channel.DefaultFileRegion;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.FileRegion;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.oio.OioClientSocketChannelFactory;
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
import java.io.IOException;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
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
    final static String SSL_HANDLER = "sslHandler";

    private final static Logger log = LogManager.getLogger(NettyAsyncHttpProvider.class);

    private final ClientBootstrap plainBootstrap;

    private final ClientBootstrap secureBootstrap;

    private final static int MAX_BUFFERED_BYTES = 8192;

    private final AsyncHttpClientConfig config;

    private final AtomicBoolean isClose = new AtomicBoolean(false);

    private final ClientSocketChannelFactory socketChannelFactory;

    private final ChannelGroup openChannels = new DefaultChannelGroup("asyncHttpClient");

    private final ConnectionsPool<String, Channel> connectionsPool;

    private final JDKAsyncHttpProvider ntlmProvider;

    private final AtomicInteger maxConnections = new AtomicInteger();

    private final NettyAsyncHttpProviderConfig asyncHttpProviderConfig;

    private boolean executeConnectAsync = false;

    public static final ThreadLocal<Boolean> IN_IO_THREAD = new ThreadLocalBoolean();    

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {
        super(new HashedWheelTimer(), 0, 0, config.getIdleConnectionTimeoutInMs(), TimeUnit.MILLISECONDS);

        if (config.getAsyncHttpProviderConfig() != null
                && NettyAsyncHttpProviderConfig.class.isAssignableFrom(config.getAsyncHttpProviderConfig().getClass())) {
            asyncHttpProviderConfig = NettyAsyncHttpProviderConfig.class.cast(config.getAsyncHttpProviderConfig());
        } else {
            asyncHttpProviderConfig = null;
        }

        if (asyncHttpProviderConfig != null && asyncHttpProviderConfig.getProperty(NettyAsyncHttpProviderConfig.USE_BLOCKING_IO) != null) {
            socketChannelFactory = new OioClientSocketChannelFactory(config.executorService());
        } else {
            socketChannelFactory = new NioClientSocketChannelFactory(
                    Executors.newCachedThreadPool(),
                    config.executorService());
        }
        plainBootstrap = new ClientBootstrap(socketChannelFactory);
        secureBootstrap = new ClientBootstrap(socketChannelFactory);

        this.config = config;

        // This is dangerous as we can't catch a wrong typed ConnectionsPool
        ConnectionsPool<String, Channel> cp = (ConnectionsPool<String, Channel>) config.getConnectionsPool();
        if (cp == null) {
            cp = new NettyConnectionsPool(config);
        }
        this.connectionsPool = cp;

        configureNetty();
        ntlmProvider = new JDKAsyncHttpProvider(config);
    }

    void configureNetty() {
        if (asyncHttpProviderConfig != null) {
            for (Entry<String, Object> entry : asyncHttpProviderConfig.propertiesSet()) {
                plainBootstrap.setOption(entry.getKey(), entry.getValue());
            }
        }

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

        if (asyncHttpProviderConfig != null){
            if (asyncHttpProviderConfig.getProperty(NettyAsyncHttpProviderConfig.EXECUTE_ASYNC_CONNECT) != null) {
                executeConnectAsync = true;
            } else if (asyncHttpProviderConfig.getProperty(NettyAsyncHttpProviderConfig.ALLOW_NESTED_REQUEST) != null) {
                DefaultChannelFuture.setUseDeadLockChecker(false);
            }
        }
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

                pipeline.addLast(HTTP_HANDLER, new HttpClientCodec());

                if (config.isCompressionEnabled()) {
                    pipeline.addLast("inflater", new HttpContentDecompressor());
                }
                pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                pipeline.addLast("httpProcessor", NettyAsyncHttpProvider.this);
                return pipeline;
            }
        });

        if (asyncHttpProviderConfig != null) {
            for (Entry<String, Object> entry : asyncHttpProviderConfig.propertiesSet()) {
                secureBootstrap.setOption(entry.getKey(), entry.getValue());
            }
        }
    }

    private Channel lookupInCache(URI uri) {
        final Channel channel = connectionsPool.removeConnection(AsyncHttpProviderUtils.getBaseUrl(uri));

        if (channel != null) {
            if (log.isDebugEnabled()) {
                log.debug(String.format(currentThread() + "Using cached Channel %s", uri, channel));
            }

            if (channel.isOpen() && channel.isBound()) {
                try {
                 return config.executorService().submit(new Callable<Boolean>() {
                    public Boolean call() {
                        try {
                           channel.setReadable(true);
                        } catch (Exception ex) {
                            if (log.isDebugEnabled()) {
                                log.debug(ex);
                            }
                            connectionsPool.removeAllConnections(channel);
                            return new Boolean(false);
                        }
                        return new Boolean(true);
                    }

                }).get().booleanValue() ? channel : null;
        } catch (Throwable e) {
        }
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
                    log.debug(currentThread() + ex.getMessage());
                    log.debug(ex);
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

    protected final <T> void writeRequest(final Channel channel, final AsyncHttpClientConfig config,
                                          final NettyResponseFuture<T> future, final HttpRequest nettyRequest) {
        try {

            if (!channel.isOpen() || !channel.isConnected()) {
                if (!remotelyClosed(channel, future)) {
                    abort(future, new ConnectException());
                    return;
                }
            }

            final Body body;
            if (future.getRequest().getBodyGenerator() != null) {
                try {
                    body = future.getRequest().getBodyGenerator().createBody();
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
                long length = body.getContentLength();
                if (length >= 0) {
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, length);
                }
            } else {
                body = null;
            }

            channel.write(nettyRequest).addListener(new ProgressListener(true, future.getAsyncHandler(), future));

            RandomAccessFile raf = null;
            if (future.getRequest().getFile() != null) {
                final File file = future.getRequest().getFile();
                long fileLength = 0;

                try{
                    raf = new RandomAccessFile(file, "r");
                    fileLength = raf.length();

                    ChannelFuture writeFuture;
                    if (channel.getPipeline().get(SslHandler.class) != null) {
                        writeFuture = channel.write(new ChunkedFile(raf, 0, fileLength, 8192));
                        writeFuture.addListener(new ProgressListener(false, future.getAsyncHandler(), future));
                    } else {
                        final FileRegion region = new DefaultFileRegion(raf.getChannel(), 0, fileLength);
                        writeFuture = channel.write(region);
                        writeFuture.addListener(new ProgressListener(false, future.getAsyncHandler(), future) {
                            public void operationComplete(ChannelFuture cf) {
                                region.releaseExternalResources();
                                super.operationComplete(cf);
                            }
                        });
                    }

                } finally {
                    if (raf != null)
                        try {
                            raf.close();
                        } catch (IOException e) {
                        }
                }
            } else if (body != null) {
                ChannelFuture writeFuture;
                if (channel.getPipeline().get(SslHandler.class) == null && (body instanceof RandomAccessBody)) {
                    writeFuture = channel.write(new BodyFileRegion((RandomAccessBody)body));
                } else {
                    writeFuture = channel.write(new BodyChunkedInput(body));
                }
                writeFuture.addListener(new ProgressListener(false, future.getAsyncHandler(), future) {
                    public void operationComplete(ChannelFuture cf) {
                        try {
                            body.close();
                        } catch (IOException e) {
                            log.warn( e, "Failed to close request body: %s", e.getMessage() );
                        }
                        super.operationComplete(cf);
                    }
                });
            }
        } catch (Throwable ioe) {
            if (future.provider().remotelyClosed(channel, future)) {
                return;
            }        
            abort(future, ioe);
        }

        try {
            future.touch();
            int delay = requestTimeout(config, future.getRequest().getPerRequestConfig());
            if (delay != -1) {
                ReaperFuture reaperFuture = new ReaperFuture(channel, future);
                Future scheduledFuture = config.reaper().scheduleAtFixedRate(reaperFuture, delay, delay, TimeUnit.MILLISECONDS);
                reaperFuture.setScheduledFuture(scheduledFuture);
                future.setReaperFuture(reaperFuture);

            }
        } catch (RejectedExecutionException ex) {
            abort(future, ex);
        }
    }

    protected final static HttpRequest buildRequest(AsyncHttpClientConfig config, Request request, URI uri,
                                                    boolean allowConnect, ChannelBuffer buffer) throws IOException {

        String method = request.getReqType();
        if (allowConnect && ((request.getProxyServer() != null || config.getProxyServer() != null) && "https".equalsIgnoreCase(uri.getScheme()))) {
            method = HttpMethod.CONNECT.toString();
        }
        return construct(config, request, new HttpMethod(method), uri, buffer);
    }

    @SuppressWarnings("deprecation")
    private static HttpRequest construct(AsyncHttpClientConfig config,
                                         Request request,
                                         HttpMethod m,
                                         URI uri,
                                         ChannelBuffer buffer) throws IOException {
        String host = uri.getHost();

        if (request.getVirtualHost() != null) {
            host = request.getVirtualHost();
        }

        HttpRequest nettyRequest;
        if (m.equals(HttpMethod.CONNECT)) {
            uri = URI.create(new StringBuilder(uri.getHost())
                    .append(":")
                    .append(AsyncHttpProviderUtils.getPort(uri)).toString());
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

        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
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
                    throw new IllegalStateException(String.format(currentThread() + "Invalid Authentication %s", realm.toString()));
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

        if (request.getHeaders().getFirstValue("User-Agent") == null && config.getUserAgent() != null) {
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
            // We already have processed the body.
            if (buffer != null) {
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, buffer.writerIndex());
                nettyRequest.setContent(buffer);
            } else if (request.getByteData() != null) {
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getByteData().length));
                nettyRequest.setContent(ChannelBuffers.copiedBuffer(request.getByteData()));
            } else if (request.getStringData() != null) {
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getStringData().length()));
                nettyRequest.setContent(ChannelBuffers.copiedBuffer(request.getStringData(), "UTF-8"));
            } else if (request.getStreamData() != null) {
                int[] lengthWrapper = new int[1];
                byte[] bytes = AsyncHttpProviderUtils.readFully(request.getStreamData(), lengthWrapper);
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

                MultipartRequestEntity mre = AsyncHttpProviderUtils.createMultipartRequestEntity(request.getParts(), request.getParams());

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
                if (!file.isFile()) {
                    throw new IOException(String.format(currentThread() + "File %s is not a file or doesn't exist", file.getAbsolutePath()));
                }
                nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, file.length());
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
        return doConnect(request, asyncHandler, null, true);
    }

    private <T> void execute(final Request request, final NettyResponseFuture<T> f) throws IOException {
        doConnect(request, f.getAsyncHandler(), f, true);
    }

    private <T> void execute(final Request request, final NettyResponseFuture<T> f, boolean useCache) throws IOException {
        doConnect(request, f.getAsyncHandler(), f, useCache);
    }

    private <T> Future<T> doConnect(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> f, boolean useCache) throws IOException {

        if (isClose.get()) {
            throw new IOException("Closed");
        }

        /**
         * Netty doesn't support NTLM, so fall back to the JDK in that case.
         */
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
        if (realm != null && realm.getScheme() == Realm.AuthScheme.NTLM) {
            if (log.isDebugEnabled()) {
                log.debug(currentThread() + "NTLM not supported by this provider. Using the " + JDKAsyncHttpProvider.class.getName());
            }
            return ntlmProvider.execute(request, asyncHandler);
        }

        URI uri = AsyncHttpProviderUtils.createUri(request.getUrl());
        Channel channel = null;

        if (useCache) {
            if (f != null && f.channel() != null) {
                channel = f.channel();
            } else {
                channel = lookupInCache(uri);
            }
        }

        if (channel != null && channel.isOpen()) {
            if (channel.isConnected()) {

                ChannelBuffer b = null;
                if (f != null && f.getRequest().getFile() == null) {
                    b = f.getNettyRequest().getContent();
                }

                HttpRequest nettyRequest = buildRequest(config, request, uri, false, b);

                if (f == null) {
                    f = new NettyResponseFuture<T>(uri, request, asyncHandler, nettyRequest,
                            requestTimeout(config, request.getPerRequestConfig()), this);
                } else {
                    f.setNettyRequest(nettyRequest);
                }
                f.setState(NettyResponseFuture.STATE.POOLED);

                if (log.isDebugEnabled()) {
                    log.debug(String.format(currentThread()
                            + "\n\nCached Request %s\n", request.toString()));
                }
                channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(f);

                writeRequest(channel, config, f, nettyRequest);
                return f;
            }
        }

        if (log.isDebugEnabled()) {
            log.debug(String.format(currentThread()
                    + "\n\nNon cached Request %s\n", request.toString()));
        }

        if (!connectionsPool.canCacheConnection() ||
                (config.getMaxTotalConnections() > -1 && (maxConnections.get() + 1) > config.getMaxTotalConnections())) {
            throw new IOException(String.format("Too many connections %s", config.getMaxTotalConnections()));
        }

        NettyConnectListener<T> c = new NettyConnectListener.Builder<T>(config, request, asyncHandler, f, this).build();
        ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();

        boolean useSSl = uri.getScheme().compareToIgnoreCase("https") == 0
                && (proxyServer == null
                || !proxyServer.getProtocolAsString().equals("https"));

        if (useSSl) {
            constructSSLPipeline(c);
        }

        if (config.getMaxTotalConnections() != -1) {
            maxConnections.incrementAndGet();
        }

        ChannelFuture channelFuture;
        ClientBootstrap bootstrap = useSSl ? secureBootstrap : plainBootstrap;
        try {
            if (proxyServer == null) {
                channelFuture = bootstrap.connect(new InetSocketAddress(uri.getHost(), AsyncHttpProviderUtils.getPort(uri)));
            } else {
                channelFuture = bootstrap.connect(new InetSocketAddress(proxyServer.getHost(), proxyServer.getPort()));
            }
            bootstrap.setOption("connectTimeout", config.getConnectionTimeoutInMs());
        } catch (Throwable t) {
            log.error(String.format(currentThread() + "doConnect"), t);
            abort(c.future(), t.getCause());
            return c.future();
        }

        boolean directInvokation = true;
        if (IN_IO_THREAD.get() && DefaultChannelFuture.isUseDeadLockChecker()) {
            directInvokation = false;
        }

        if (directInvokation && !executeConnectAsync && request.getFile() == null) {
            channelFuture.awaitUninterruptibly();
            try {
                c.operationComplete(channelFuture);
            } catch (Exception e) {
                IOException ioe = new IOException(e.getMessage());
                ioe.initCause(e);
                throw ioe;
            }
        } else {
            channelFuture.addListener(c);
        }

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
        if (log.isDebugEnabled()) {
            log.debug(String.format(currentThread() + "Channel Idle: %s", ctx.getChannel()));
        }
        NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();

        abort(future, new IOException("No response received. Connection timed out after " + config.getIdleConnectionTimeoutInMs()));
        closeChannel(ctx);
    }

    private void closeChannel(final ChannelHandlerContext ctx) {
        if (config.getMaxTotalConnections() != -1) {
            maxConnections.decrementAndGet();
        }
        connectionsPool.removeAllConnections(ctx.getChannel());
        ctx.setAttachment(new DiscardEvent());
        config.executorService().submit(new Runnable() {
            public void run() {
                ctx.getChannel().close().awaitUninterruptibly();
            }
        });
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        //call super to reset the read timeout
        super.messageReceived(ctx, e);

        IN_IO_THREAD.set(Boolean.TRUE);        
        if (log.isDebugEnabled()) {
            log.debug(String.format(currentThread() + "Message Received %s. Attachment Type is %s",
                    e.getClass().getName(), ctx.getAttachment() != null ?
                            ctx.getAttachment().getClass().getName() : "No attach"));

            if (ctx.getAttachment() == null) {
                log.warn(currentThread() + "ChannelHandlerContext wasn't having any attachment");
            }
        }

        if (ctx.getAttachment() instanceof DiscardEvent) {
             config.executorService().submit(new Callable<Boolean>() {
                    public Boolean call() {
                        try {
                           ctx.getChannel().setReadable(true);
                        } catch (Exception ex) {
                            if (log.isDebugEnabled()) {
                                log.debug(ex);
                            }
                            connectionsPool.removeAllConnections(ctx.getChannel());
                            return new Boolean(false);
                        }
                        return new Boolean(true);
                    }
                }).get();
            return;
        } else if (ctx.getAttachment() instanceof AsyncCallable) {
            HttpChunk chunk = (HttpChunk) e.getMessage();
            if (chunk.isLast()) {
                AsyncCallable ac = (AsyncCallable) ctx.getAttachment();
                ctx.setAttachment(ac.future());
                ac.call();
            }
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

                if (log.isDebugEnabled()) {
                    log.debug(String.format(currentThread()
                            + "\n\nRequest %s\n\nResponse %s\n", nettyRequest.toString(), response.toString()));
                }

                // Required if there is some trailing headers.
                future.setHttpResponse(response);
                int statusCode = response.getStatus().getCode();

                String ka = response.getHeader(HttpHeaders.Names.CONNECTION);
                future.setKeepAlive(ka == null || ka.toLowerCase().equals("keep-alive"));

                String wwwAuth = response.getHeader(HttpHeaders.Names.WWW_AUTHENTICATE);
                Request request = future.getRequest();
                Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
                if (statusCode == 401
                        && wwwAuth != null
                        && realm != null
                        && !future.getAndSetAuth(true)) {

                    final Realm nr = new Realm.RealmBuilder().clone(realm)
                            .setScheme(realm.getAuthScheme())                            
                            .setUri(URI.create(request.getUrl()).getPath())
                            .setMethodName(request.getReqType())
                            .setUsePreemptiveAuth(true)
                            .parseWWWAuthenticateHeader(wwwAuth)                            
                            .build();

                    if (log.isDebugEnabled()) {
                        log.debug(String.format(currentThread() + "Sending authentication to %s", request.getUrl()));
                    }

                    if (config.getKeepAlive()) {
                        future.attachChannel(ctx.getChannel());
                    }
                    final RequestBuilder builder = new RequestBuilder(future.getRequest());
                    future.setState(NettyResponseFuture.STATE.NEW);

                    // We must consume the body first in order to re-use the connection.
                    if (response.isChunked()) {
                        ctx.setAttachment(new AsyncCallable(future) {
                            public Object call() throws Exception {
                                nextRequest(builder.setRealm(nr).build(), future);
                                return null;
                            }
                        });
                    } else {
                        nextRequest(builder.setRealm(nr).build(), future);
                    }
                    return;
                }

                String proxyAuth = response.getHeader(HttpHeaders.Names.PROXY_AUTHENTICATE);
                if (statusCode == 407
                        && proxyAuth != null
                        && future.getRequest().getRealm() != null
                        && !future.getAndSetAuth(true)) {

                    if (log.isDebugEnabled()) {
                        log.debug(String.format(currentThread() + "Sending proxy authentication to %s", request.getUrl()));
                    }

                    if (response.isChunked()) {
                        ctx.setAttachment(new AsyncCallable(future) {
                            public Object call() throws Exception {
                                nextRequest(future.getRequest(), future);
                                return null;
                            }
                        });
                    } else {
                        nextRequest(future.getRequest(), future);
                    }
                    return;
                }

                if (future.getNettyRequest().getMethod().equals(HttpMethod.CONNECT)
                        && statusCode == 200) {

                    if (log.isDebugEnabled()) {
                        log.debug(String.format(currentThread() + "Connected to %s", request.getUrl()));
                    }

                    if (config.getKeepAlive()) {
                        future.attachChannel(ctx.getChannel());
                    }

                    final RequestBuilder builder = new RequestBuilder(future.getRequest());
                    try {
                        upgradeProtocol(ctx.getChannel().getPipeline(), (request.getUrl()));
                    } catch (Throwable ex) {
                        abort(future, ex);
                    }

                    nextRequest(builder.build(), future);
                    return;
                }

                boolean redirectEnabled = request.isRedirectEnabled() ? true : config.isRedirectEnabled();
                if (redirectEnabled && (statusCode == 302 || statusCode == 301)) {

                    if (future.incrementAndGetCurrentRedirectCount() < config.getMaxRedirects()) {

                        String location = response.getHeader(HttpHeaders.Names.LOCATION);
                        if (location.startsWith("/")) {
                            location = AsyncHttpProviderUtils.getBaseUrl(future.getURI()) + location;
                        }

                        if (!location.equalsIgnoreCase(future.getURI().toString())) {
                            URI uri = AsyncHttpProviderUtils.createUri(location);

                            final RequestBuilder builder = new RequestBuilder(future.getRequest());
                            final URI initialConnectionUri = future.getURI();
                            final boolean initialConnectionKeepAlive = future.getKeepAlive();
                            future.setURI(uri);
                            final String newUrl = uri.toString();

                            if (log.isDebugEnabled()) {
                                log.debug(String.format(currentThread() + "Redirecting to %s", newUrl));
                            }

                            if (response.isChunked()) {
                                ctx.setAttachment(new AsyncCallable(future) {
                                    public Object call() throws Exception {
                                        nextRequest(builder.setUrl(newUrl).build(), future);
                                        if (initialConnectionKeepAlive) {
                                            connectionsPool.addConnection(AsyncHttpProviderUtils.getBaseUrl(initialConnectionUri), ctx.getChannel());
                                        } else {
                                            closeChannel(ctx);
                                        }
                                        return null;
                                    }
                                });
                            } else {
                                nextRequest(builder.setUrl(newUrl).build(), future);
                                if (initialConnectionKeepAlive) {
                                    connectionsPool.addConnection(AsyncHttpProviderUtils.getBaseUrl(initialConnectionUri), ctx.getChannel());
                                } else {
                                    closeChannel(ctx);
                                }
                            }
                            return;
                        }
                    } else {
                        throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
                    }
                }

                if (!future.getAndSetStatusReceived(true) && updateStatusAndInterrupt(handler, new ResponseStatus(future.getURI(), response, this))) {
                    finishUpdate(future, ctx, response.isChunked());

                    return;
                } else if (updateHeadersAndInterrupt(handler, new ResponseHeaders(future.getURI(), response, this))) {
                    finishUpdate(future, ctx, response.isChunked());
                    return;
                } else if (!response.isChunked()) {
                    if (response.getContent().readableBytes() != 0) {
                        updateBodyAndInterrupt(handler, new ResponseBodyPart(future.getURI(), response, this));
                    }
                    finishUpdate(future, ctx, false);
                    return;
                }

                if (nettyRequest.getMethod().equals(HttpMethod.HEAD)) {
                    markAsDoneAndCacheConnection(future, ctx, ctx.getChannel().isReadable());
                }

            } else if (e.getMessage() instanceof HttpChunk) {
                HttpChunk chunk = (HttpChunk) e.getMessage();

                if (handler != null) {
                    if (chunk.isLast() || updateBodyAndInterrupt(handler, new ResponseBodyPart(future.getURI(), null, this, chunk))) {
                        if (chunk instanceof DefaultHttpChunkTrailer) {
                            updateHeadersAndInterrupt(handler, new ResponseHeaders(future.getURI(),
                                    future.getHttpResponse(), this, (HttpChunkTrailer) chunk));
                        }
                        finishUpdate(future, ctx, !chunk.isLast());
                    }
                }
            }
        } catch (Exception t) {
            try {
                abort(future, t);
            } finally {
                finishUpdate(future, ctx, false);
                throw t;
            }
        }
    }

    private void nextRequest(final Request request, final NettyResponseFuture<?> future) throws IOException {
        if (!executeConnectAsync && request.getFile() == null) {
            config.executorService().submit(new Runnable(){
                public void run() {
                    try {
                        execute(request, future);
                    } catch (IOException e) {
                        if (log.isDebugEnabled()) {
                            log.debug(e);   
                        }
                    }
                }
            });
        } else {
            execute(request, future);
        }
    }

    private void abort(NettyResponseFuture<?> future, Throwable t) {
        if (config.getMaxTotalConnections() != -1) {
            maxConnections.decrementAndGet();
        }

        if (log.isDebugEnabled()) {
            log.debug(String.format(currentThread() + "abording Future %s", future));
            log.debug(t);
        }

        future.abort(t);
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

        connectionsPool.removeAllConnections(ctx.getChannel());        

        Exception exception = null;
        try {
            super.channelClosed(ctx, e);
        } catch (Exception ex) {
            exception = ex;
        }

        if (log.isDebugEnabled()) {
            log.debug(String.format(currentThread() + "Channel Closed: %s", e.getChannel()));
        }

        if (ctx.getAttachment() instanceof AsyncCallable) {
            AsyncCallable ac = (AsyncCallable) ctx.getAttachment();
            ctx.setAttachment(ac.future());
            ac.call();
            return;
        }

        if (!isClose.get() && ctx.getAttachment() instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();
            if (future != null && !future.isDone()) {
                remotelyClosed(ctx.getChannel(), future);
            }
        }
    }

    protected boolean remotelyClosed(Channel channel, NettyResponseFuture<?> future) {
        if (isClose.get()) {
            return false;
        }

        connectionsPool.removeAllConnections(channel);

        if (future == null && channel.getPipeline().getContext(NettyAsyncHttpProvider.class).getAttachment() != null
                && NettyResponseFuture.class.isAssignableFrom(
                channel.getPipeline().getContext(NettyAsyncHttpProvider.class).getAttachment().getClass())) {
            future = (NettyResponseFuture<?>)
                    channel.getPipeline().getContext(NettyAsyncHttpProvider.class).getAttachment();
        }

        if (future == null) {
            return false;
        }

        if (!config.getKeepAlive() || future.isDone() || !future.canRetry() || future.isCancelled()) {
            return false;
        }

        future.setState(NettyResponseFuture.STATE.RECONNECTED);

        if (log.isDebugEnabled()) {
            log.debug(String.format(currentThread() + "Trying to recover request %s", future.getNettyRequest()));
        }

        try {
            nextRequest(future.getRequest(), future);
            return true;
        } catch (IOException iox) {
            future.setState(NettyResponseFuture.STATE.CLOSED);
            future.abort(iox);
            log.error(String.format(currentThread() + "Remotely Closed"), iox);
        }
        return false;
    }

    private void markAsDoneAndCacheConnection(final NettyResponseFuture<?> future, final ChannelHandlerContext ctx,
                                              final boolean cache) throws MalformedURLException {
        // We need to make sure everything is OK before adding the connection back to the pool.
        try {
            future.done(new Callable<Boolean>() {
                public Boolean call() throws Exception {
                    if (future.getKeepAlive() && cache) {
                        connectionsPool.addConnection(AsyncHttpProviderUtils.getBaseUrl(future.getURI()), ctx.getChannel());
                    }
                    return false;
                }
            });
        } catch (Throwable t) {
            // Never propagate exception once we know we are done.
            if (log.isDebugEnabled()) {
                log.debug(currentThread(), t);
            }
        }

        if (!future.getKeepAlive()) {
            closeChannel(ctx);
        }
    }

    private void finishUpdate(final NettyResponseFuture<?> future, final ChannelHandlerContext ctx, boolean isChunked) throws IOException {
        if (isChunked && future.getKeepAlive()) {
            ctx.setAttachment(new AsyncCallable(future) {
                public Object call() throws Exception {
                    markAsDoneAndCacheConnection(future, ctx, ctx.getChannel().isReadable());
                    return null;
                }
            });
        } else {
            markAsDoneAndCacheConnection(future, ctx, markChannelNotReadable(ctx));
        }
    }

    private boolean markChannelNotReadable(final ChannelHandlerContext ctx) {
        // Catch any unexpected exception when marking the channel.        
        ctx.setAttachment(new DiscardEvent());
        try {
            return config.executorService().submit(new Callable<Boolean>() {
                    public Boolean call() {
                        try {
                            ctx.getChannel().setReadable(false);
                        } catch (Exception ex) {
                            if (log.isDebugEnabled()) {
                                log.debug(ex);
                            }
                            connectionsPool.removeAllConnections(ctx.getChannel());
                            return new Boolean(false);
                        }
                        return new Boolean(true);
                    }

                }).get().booleanValue();
        } catch (Throwable e) {
        }

        return false;
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

    final static class DiscardEvent {
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
            throws Exception {
        Channel channel = e.getChannel();
        Throwable cause = e.getCause();
        NettyResponseFuture<?> future = null;

        try {

            if (cause != null && ClosedChannelException.class.isAssignableFrom(cause.getClass())) {
                return;
            }

            if (ctx.getAttachment() instanceof NettyResponseFuture<?>) {
                future = (NettyResponseFuture<?>) ctx.getAttachment();
                future.attachChannel(null);

                if (abortOnReadCloseException(cause) || abortOnWriteCloseException(cause)) {
                    log.debug(currentThread() + String.format("Trying to recover from dead Channel: %s ", channel));
                    if (remotelyClosed(channel, future)) {
                        return;
                    }
                }
            } else if (ctx.getAttachment() instanceof AsyncCallable) {
                future = ((AsyncCallable) ctx.getAttachment()).future();
            }
        } catch (Throwable t) {
            cause = t;
        }

        if (future != null) {
            try {
                abort(future, cause);
            } catch (Throwable t) {
                log.error(currentThread(), t);
            }
        }

        if (log.isDebugEnabled()) {
            log.error(currentThread() + String.format("Exception Caught: %s Attachment was %s",
                    cause != null ? cause.getMessage() : "unavailable cause",
                    ctx.getAttachment()));
            log.error(cause);
        }
        
    }


    protected static boolean abortOnConnectCloseException(Throwable cause) {
        try {
            for (StackTraceElement element : cause.getStackTrace()) {
                if (element.getClassName().equals("sun.nio.ch.SocketChannelImpl")
                        && element.getMethodName().equals("checkConnect")) {
                    return true;
                }
            }

            if (cause.getCause() != null) {
                return abortOnConnectCloseException(cause.getCause());
            }

        } catch (Throwable t) {}
        return false;
    }

    protected static boolean abortOnReadCloseException(Throwable cause) {

        for (StackTraceElement element : cause.getStackTrace()) {
            if (element.getClassName().equals("sun.nio.ch.SocketDispatcher")
                    && element.getMethodName().equals("read")) {
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
            if (element.getClassName().equals("sun.nio.ch.SocketDispatcher")
                    && element.getMethodName().equals("write")) {
                return true;
            }
        }

        if (cause.getCause() != null) {
            return abortOnReadCloseException(cause.getCause());
        }

        return false;
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


    private static class ProgressListener implements ChannelFutureProgressListener {

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

                if (ClosedChannelException.class.isAssignableFrom(cause.getClass())
                        || abortOnReadCloseException(cause)
                        || abortOnWriteCloseException(cause)) {

                    if (log.isDebugEnabled()) {
                        log.debug(currentThread(), cf.getCause());
                    }

                    if (future.provider().remotelyClosed(cf.getChannel(), future)) {
                        return;
                    } else {
                        future.abort(cause);
                    }
                } else {
                    future.abort(cause);
                }
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
                ProgressAsyncHandler.class.cast(asyncHandler).onContentWriteProgress(amount, current, total);
            }
        }
    }

    final static String currentThread() {
        return AsyncHttpProviderUtils.currentThread();
    }

    /**
     * Because some implementation of the ThreadSchedulingService do not clean up cancel task until they try to run
     * them, we wrap the task with the future so the when the NettyResponseFuture cancel the reaper future
     * this wrapper will release the references to the channel and the nettyResponseFuture immediately. Otherwise,
     * the memory referenced this way will only be released after the request timeout period which can be arbitrary long.
     */
    private final class ReaperFuture implements Future, Runnable {
        private Future scheduledFuture;
        private Channel channel;
        private NettyResponseFuture nettyResponseFuture;

        public ReaperFuture(Channel channel, NettyResponseFuture nettyResponseFuture) {
            this.channel = channel;
            this.nettyResponseFuture = nettyResponseFuture;
        }

        public void setScheduledFuture(Future scheduledFuture) {
            this.scheduledFuture = scheduledFuture;
        }

        /**
         * @Override
         */
        public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            //cleanup references to allow gc to reclaim memory independently
            //of this Future lifecycle
            this.channel = null;
            this.nettyResponseFuture = null;
            return this.scheduledFuture.cancel(mayInterruptIfRunning);
        }

        /**
         * @Override
         */
        public Object get() throws InterruptedException, ExecutionException {
            return this.scheduledFuture.get();
        }

        /**
         * @Override
         */
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException,
                TimeoutException {
            return this.scheduledFuture.get(timeout, unit);
        }

        /**
         * @Override
         */
        public boolean isCancelled() {
            return this.scheduledFuture.isCancelled();
        }

        /**
         * @Override
         */
        public boolean isDone() {
            return this.scheduledFuture.isDone();
        }

        /**
         * @Override
         */
        public synchronized void run() {
            if (this.nettyResponseFuture != null && this.nettyResponseFuture.hasExpired()) {
                if (log.isDebugEnabled()) {
                    log.debug(currentThread() + "Request Timeout expired for " + this.nettyResponseFuture);
                }
                int requestTimeout = config.getRequestTimeoutInMs();
                PerRequestConfig p = this.nettyResponseFuture.getRequest().getPerRequestConfig();
                if (p != null && p.getRequestTimeoutInMs() != -1) {
                    requestTimeout = p.getRequestTimeoutInMs();
                }
                abort(this.nettyResponseFuture, new TimeoutException(String.format("No response received after %s", requestTimeout)));
                markChannelNotReadable(channel.getPipeline().getContext(NettyAsyncHttpProvider.class));

                this.nettyResponseFuture = null;
                this.channel = null;
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
            return defaultValue? Boolean.TRUE : Boolean.FALSE;
        }
    }
}

