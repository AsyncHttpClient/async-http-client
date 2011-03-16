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
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.FilterException;
import com.ning.http.client.filter.IOExceptionFilter;
import com.ning.http.client.filter.ResponseFilter;
import com.ning.http.client.listener.TransferCompletionHandler;
import com.ning.http.client.ntlm.NTLMEngine;
import com.ning.http.client.ntlm.NTLMEngineException;
import com.ning.http.client.providers.netty.spnego.SpnegoEngine;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.AuthenticatorUtils;
import com.ning.http.util.CleanupChannelGroup;
import com.ning.http.util.ProxyUtils;
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
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.FileRegion;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
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
import org.jboss.netty.handler.codec.http.HttpContentCompressor;
import org.jboss.netty.handler.codec.http.HttpContentDecompressor;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedFile;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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

import static com.ning.http.util.AsyncHttpProviderUtils.DEFAULT_CHARSET;
import static org.jboss.netty.channel.Channels.pipeline;

public class NettyAsyncHttpProvider extends SimpleChannelUpstreamHandler implements AsyncHttpProvider<HttpResponse> {
    private final static String HTTP_HANDLER = "httpHandler";
    final static String SSL_HANDLER = "sslHandler";
    private final static String HTTPS = "https";
    private final static String HTTP = "http";

    private final static Logger log = LoggerFactory.getLogger(NettyAsyncHttpProvider.class);

    private final ClientBootstrap plainBootstrap;

    private final ClientBootstrap secureBootstrap;

    private final static int MAX_BUFFERED_BYTES = 8192;

    private final AsyncHttpClientConfig config;

    private final AtomicBoolean isClose = new AtomicBoolean(false);

    private final ClientSocketChannelFactory socketChannelFactory;

    private final ChannelGroup openChannels = new
            CleanupChannelGroup("asyncHttpClient") {
                @Override
                public boolean remove(Object o) {
                    boolean removed = super.remove(o);
                    if( removed ) {
                        maxConnections.decrementAndGet();
                    }
                    return removed;
                }
            };

    private final ConnectionsPool<String, Channel> connectionsPool;

    private final AtomicInteger maxConnections = new AtomicInteger();

    private final NettyAsyncHttpProviderConfig asyncHttpProviderConfig;

    private boolean executeConnectAsync = false;

    public static final ThreadLocal<Boolean> IN_IO_THREAD = new ThreadLocalBoolean();

    private final boolean trackConnections;

    private final static NTLMEngine ntlmEngine = new NTLMEngine();

    private final static SpnegoEngine spnegoEngine = new SpnegoEngine();

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {

        if (config.getAsyncHttpProviderConfig() != null
                && NettyAsyncHttpProviderConfig.class.isAssignableFrom(config.getAsyncHttpProviderConfig().getClass())) {
            asyncHttpProviderConfig = NettyAsyncHttpProviderConfig.class.cast(config.getAsyncHttpProviderConfig());
        } else {
            asyncHttpProviderConfig = new NettyAsyncHttpProviderConfig();
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
        if (cp == null && config.getAllowPoolingConnection()) {
            cp = new NettyConnectionsPool(this);
        } else if (cp == null) {
            cp = new NonConnectionsPool();
        }
        this.connectionsPool = cp;

        configureNetty();
        trackConnections = (config.getMaxTotalConnections() != -1);
    }

    @Override
    public String toString() {
        return String.format("NettyAsyncHttpProvider:\n\t- maxConnections: %d\n\t- openChannels: %s\n\t- connectionPools: %s",
                maxConnections.get(),
                openChannels.toString(),
                connectionsPool.toString());
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
        });
        DefaultChannelFuture.setUseDeadLockChecker(false);

        if (asyncHttpProviderConfig != null) {
            if (asyncHttpProviderConfig.getProperty(NettyAsyncHttpProviderConfig.EXECUTE_ASYNC_CONNECT) != null) {
                executeConnectAsync = true;
            } else if (asyncHttpProviderConfig.getProperty(NettyAsyncHttpProviderConfig.DISABLE_NESTED_REQUEST) != null) {
                DefaultChannelFuture.setUseDeadLockChecker(true);
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
        final Channel channel = connectionsPool.poll(AsyncHttpProviderUtils.getBaseUrl(uri));

        if (channel != null) {
            log.debug("Using cached Channel {}\n for uri {}\n", channel, uri);

            try {
                // Always make sure the channel who got cached support the proper protocol. It could
                // only occurs when a HttpMethod.CONNECT is used agains a proxy that require upgrading from http to
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
        } else if (channel.getPipeline().get(SSL_HANDLER) == null && HTTPS.equalsIgnoreCase(scheme)) {
            channel.getPipeline().addFirst(SSL_HANDLER, new SslHandler(createSSLEngine()));
        }
        return channel;
    }

    protected final <T> void writeRequest(final Channel channel,
                                          final AsyncHttpClientConfig config,
                                          final NettyResponseFuture<T> future,
                                          final HttpRequest nettyRequest) {
        try {
            /**
             * If the channel is dead because it was pooled and the remote server decided to close it,
             * we just let it go and the closeChannel do it's work.
             */
            if (!channel.isOpen() || !channel.isConnected()) {
                return;                
            }

            Body body = null;
            if (!future.getNettyRequest().getMethod().equals(HttpMethod.CONNECT)) {
                if (future.getRequest().getBodyGenerator() != null) {
                    try {
                        body = future.getRequest().getBodyGenerator().createBody();
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                    long length = body.getContentLength();
                    if (length >= 0) {
                        nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, length);
                    } else {
                        nettyRequest.setHeader(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
                    }
                } else {
                    body = null;
                }
            }

            if (TransferCompletionHandler.class.isAssignableFrom(future.getAsyncHandler().getClass())) {

                FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
                for (String s : future.getNettyRequest().getHeaderNames()) {
                    for (String header : future.getNettyRequest().getHeaders(s)) {
                        h.add(s, header);
                    }
                }

                TransferCompletionHandler.class.cast(future.getAsyncHandler()).transferAdapter(
                        new NettyTransferAdapter(h, nettyRequest.getContent(), future.getRequest().getFile()));
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
                                writeFuture = channel.write(new ChunkedFile(raf, 0, fileLength, 8192));
                                writeFuture.addListener(new ProgressListener(false, future.getAsyncHandler(), future));
                            } else {
                                final FileRegion region = new OptimizedFileRegion(raf, 0, fileLength);
                                writeFuture = channel.write(region);
                                writeFuture.addListener(new ProgressListener(false, future.getAsyncHandler(), future));
                            }
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
                            writeFuture = channel.write(new BodyFileRegion((RandomAccessBody) body));
                        } else {
                            writeFuture = channel.write(new BodyChunkedInput(body));
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
            int delay = requestTimeout(config, future.getRequest().getPerRequestConfig());
            if (delay != -1) {
                ReaperFuture reaperFuture = new ReaperFuture(channel, future);
                Future scheduledFuture = config.reaper().scheduleAtFixedRate(reaperFuture, delay, 500, TimeUnit.MILLISECONDS);
                reaperFuture.setScheduledFuture(scheduledFuture);
                future.setReaperFuture(reaperFuture);
            }
        } catch (RejectedExecutionException ex) {
            abort(future, ex);
        }

    }

    protected final static HttpRequest buildRequest(AsyncHttpClientConfig config, Request request, URI uri,
                                                    boolean allowConnect, ChannelBuffer buffer) throws IOException {

        String method = request.getMethod();
        if (allowConnect && ((request.getProxyServer() != null || config.getProxyServer() != null) && HTTPS.equalsIgnoreCase(uri.getScheme()))) {
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
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_0, m, AsyncHttpProviderUtils.getAuthority(uri));
        } else if (config.getProxyServer() != null || request.getProxyServer() != null) {
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, m, uri.toString());
        } else {
            StringBuilder path = new StringBuilder(uri.getRawPath());
            if (uri.getQuery() != null) {
                path.append("?").append(uri.getRawQuery());
            }
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, m, path.toString());
        }

        if (host != null) {
            if (uri.getPort() == -1) {
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
        ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
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
                case NTLM:
                    try {
                        nettyRequest.setHeader(HttpHeaders.Names.AUTHORIZATION,
                                ntlmEngine.generateType1Msg("NTLM " + realm.getNtlmDomain(), realm.getNtlmHost()));
                    } catch (NTLMEngineException e) {
                        IOException ie = new IOException();
                        ie.initCause(e);
                        throw ie;
                    }
                    break;
                case KERBEROS:
                case SPNEGO:
                    String challengeHeader = null;
                    String authServer = proxyServer == null ? AsyncHttpProviderUtils.getBaseUrl(uri) : proxyServer.getHost();
                    try {
                        challengeHeader = spnegoEngine.generateToken(authServer);
                    } catch (Throwable e) {
                        IOException ie = new IOException();
                        ie.initCause(e);
                        throw ie;
                    }
                    nettyRequest.setHeader(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);
                    break;
                default:
                    throw new IllegalStateException(String.format("Invalid Authentication %s", realm.toString()));
            }
        }

        if (!request.getHeaders().containsKey(HttpHeaders.Names.CONNECTION)) {
            nettyRequest.setHeader(HttpHeaders.Names.CONNECTION, "keep-alive");
        }

        boolean avoidProxy = ProxyUtils.avoidProxy(proxyServer, request);
        if (!avoidProxy) {
            if (!request.getHeaders().containsKey("Proxy-Connection")) {
                nettyRequest.setHeader("Proxy-Connection", "keep-alive");
            }

            if (proxyServer.getPrincipal() != null) {
                nettyRequest.setHeader(HttpHeaders.Names.PROXY_AUTHORIZATION,
                        AuthenticatorUtils.computeBasicAuthentication(proxyServer));
            }
        }

        // Add default accept headers.
        if (request.getHeaders().getFirstValue("Accept") == null) {
            nettyRequest.setHeader(HttpHeaders.Names.ACCEPT, "*/*");
        }

        if (request.getHeaders().getFirstValue("User-Agent") == null && config.getUserAgent() != null) {
            nettyRequest.setHeader("User-Agent", config.getUserAgent());
        } else if (config.getUserAgent() != null) {
            nettyRequest.setHeader("User-Agent", config.getUserAgent());
        } else if (request.getHeaders().getFirstValue("User-Agent") != null) {
            nettyRequest.setHeader("User-Agent", request.getHeaders().getFirstValue("User-Agent"));
        } else {
            nettyRequest.setHeader("User-Agent", AsyncHttpProviderUtils.constructUserAgent(NettyAsyncHttpProvider.class));
        }

        if (!m.equals(HttpMethod.CONNECT)) {
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

            String reqType = request.getMethod();
            if (!"GET".equals(reqType) && !"HEAD".equals(reqType) && !"OPTION".equals(reqType) && !"TRACE".equals(reqType)) {

                String bodyCharset = request.getBodyEncoding() == null ? DEFAULT_CHARSET : request.getBodyEncoding();

                // We already have processed the body.
                if (buffer != null && buffer.writerIndex() != 0) {
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, buffer.writerIndex());
                    nettyRequest.setContent(buffer);
                } else if (request.getByteData() != null) {
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getByteData().length));
                    nettyRequest.setContent(ChannelBuffers.copiedBuffer(request.getByteData()));
                } else if (request.getStringData() != null) {
                    nettyRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getStringData().getBytes(bodyCharset).length));
                    nettyRequest.setContent(ChannelBuffers.copiedBuffer(request.getStringData(), bodyCharset));
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
                    nettyRequest.setContent(ChannelBuffers.copiedBuffer(sb.toString().getBytes(bodyCharset)));

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

            for(Channel channel: openChannels) {
                ChannelHandlerContext ctx = channel.getPipeline().getContext(NettyAsyncHttpProvider.class);
                if (ctx.getAttachment() instanceof NettyResponseFuture<?>) {
                    NettyResponseFuture<?> future = (NettyResponseFuture<?>) ctx.getAttachment();
                    future.setReaperFuture(null);
                }
            }

            config.executorService().shutdown();
            config.reaper().shutdown();
            socketChannelFactory.releaseExternalResources();
            plainBootstrap.releaseExternalResources();
            secureBootstrap.releaseExternalResources();
        } catch (Throwable t) {
            log.warn("Unexpected error on close", t);
        }
    }

    /* @Override */

    public Response prepareResponse(final HttpResponseStatus status,
                                    final HttpResponseHeaders headers,
                                    final Collection<HttpResponseBodyPart> bodyParts) {
        return new NettyResponse(status, headers, bodyParts);
    }

    /* @Override */

    public <T> ListenableFuture<T> execute(Request request, final AsyncHandler<T> asyncHandler) throws IOException {
        return doConnect(request, asyncHandler, null, true, executeConnectAsync);
    }

    private <T> void execute(final Request request, final NettyResponseFuture<T> f, boolean useCache, boolean asyncConnect) throws IOException {
        doConnect(request, f.getAsyncHandler(), f, useCache, asyncConnect);
    }

    private <T> ListenableFuture<T> doConnect(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> f, boolean useCache, boolean asyncConnect) throws IOException {

        if (isClose.get()) {
            throw new IOException("Closed");
        }

        ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
        URI uri = AsyncHttpProviderUtils.createUri(request.getUrl());
        Channel channel = null;

        if (useCache) {
            if (f != null && f.reuseChannel() && f.channel() != null) {
                channel = f.channel();
            } else {
                channel = lookupInCache(uri);
            }
        }

        ChannelBuffer bufferedBytes = null;
        if (f != null && f.getRequest().getFile() == null &&
                !f.getNettyRequest().getMethod().getName().equals(HttpMethod.CONNECT.getName())) {
            bufferedBytes = f.getNettyRequest().getContent();
        }

        boolean useSSl = uri.getScheme().compareToIgnoreCase(HTTPS) == 0 && proxyServer == null;
        if (channel != null && channel.isOpen() && channel.isConnected()) {
            HttpRequest nettyRequest = buildRequest(config, request, uri, false, bufferedBytes);

            if (f == null) {
                f = newFuture(uri, request, asyncHandler, nettyRequest, config, this);
            } else {
                f.setNettyRequest(nettyRequest);
            }
            f.setState(NettyResponseFuture.STATE.POOLED);
            f.attachChannel(channel, false);

            log.debug("\nUsing cached Channel {}\n for request \n{}\n", channel, nettyRequest);
            channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(f);

            try {
                writeRequest(channel, config, f, nettyRequest);
            } catch (IllegalStateException ex) {
                log.debug("writeRequest failure", ex);
                if (useSSl && ex.getMessage() != null && ex.getMessage().contains("SSLEngine")) {
                    log.debug("SSLEngine failure", ex);
                    f = null;
                } else {
                    throw ex;
                }
            }
            return f;
        }

        if (!connectionsPool.canCacheConnection() ||
                (config.getMaxTotalConnections() > -1 && (maxConnections.get() + 1) > config.getMaxTotalConnections())) {
            IOException ex = new IOException(String.format("Too many connections %s", config.getMaxTotalConnections()));
            try {
                asyncHandler.onThrowable(ex);   
            } catch (Throwable t) {
                log.warn("asyncHandler.onThrowable",t);
            }
            throw ex;
        }

        NettyConnectListener<T> c = new NettyConnectListener.Builder<T>(config, request, asyncHandler, f, this, bufferedBytes).build(uri);
        boolean avoidProxy = ProxyUtils.avoidProxy(proxyServer, uri.getHost());

        if (useSSl) {
            constructSSLPipeline(c);
        }

        if (trackConnections) {
            maxConnections.incrementAndGet();
        }

        ChannelFuture channelFuture;
        ClientBootstrap bootstrap = useSSl ? secureBootstrap : plainBootstrap;
        bootstrap.setOption("connectTimeoutMillis", config.getConnectionTimeoutInMs());

        // Do no enable this with win.
        if (System.getProperty("os.name").toLowerCase().indexOf("win") == -1) {
            bootstrap.setOption("reuseAddress", asyncHttpProviderConfig.getProperty(NettyAsyncHttpProviderConfig.REUSE_ADDRESS));
        }

        try {
            if (proxyServer == null || avoidProxy) {
                channelFuture = bootstrap.connect(new InetSocketAddress(uri.getHost(), AsyncHttpProviderUtils.getPort(uri)));
            } else {
                channelFuture = bootstrap.connect(new InetSocketAddress(proxyServer.getHost(), proxyServer.getPort()));
            }
        } catch (Throwable t) {
            abort(c.future(), t.getCause() == null ? t : t.getCause());
            return c.future();
        }

        boolean directInvokation = true;
        if (IN_IO_THREAD.get() && DefaultChannelFuture.isUseDeadLockChecker()) {
            directInvokation = false;
        }

        if (directInvokation && !asyncConnect && request.getFile() == null) {
            int timeOut = config.getConnectionTimeoutInMs() > 0 ? config.getConnectionTimeoutInMs() : Integer.MAX_VALUE;
            if (!channelFuture.awaitUninterruptibly(timeOut, TimeUnit.MILLISECONDS)) {
                abort(c.future(), new ConnectException("Connect times out"));
            }

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

        log.debug("\nNon cached request \n{}\n\nusing Channel \n{}\n", c.future().getNettyRequest(), channelFuture.getChannel());

        if (!c.future().isCancelled() || !c.future().isDone()) {
            openChannels.add(channelFuture.getChannel());
            c.future().attachChannel(channelFuture.getChannel(), false);
        }
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

    private void closeChannel(final ChannelHandlerContext ctx) {
        if (trackConnections && openChannels.contains(ctx.getChannel())) {
            maxConnections.decrementAndGet();
        }
        connectionsPool.removeAll(ctx.getChannel());
        finishChannel(ctx);
    }

    private void finishChannel(final ChannelHandlerContext ctx) {
        log.debug("Closing Channel {} ", ctx.getChannel());
        
        ctx.setAttachment(new DiscardEvent());

        // The channel may have already been removed if a timeout occurred, and this method may be called just after.
        if (ctx.getChannel() == null) {
            return;
        }

        try {
            ctx.getChannel().close();
        } catch (Throwable t) {
            log.debug("Error closing a connection", t);
        }
        openChannels.remove(ctx.getChannel());
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        //call super to reset the read timeout
        super.messageReceived(ctx, e);

        IN_IO_THREAD.set(Boolean.TRUE);
        if (ctx.getAttachment() == null) {
            log.debug("ChannelHandlerContext wasn't having any attachment");
        }

        if (ctx.getAttachment() instanceof DiscardEvent) {
            return;
        } else if (ctx.getAttachment() instanceof AsyncCallable) {
            HttpChunk chunk = (HttpChunk) e.getMessage();
            if (chunk.isLast()) {
                AsyncCallable ac = (AsyncCallable) ctx.getAttachment();
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
        Request request = future.getRequest();
        HttpResponse response = null;
        try {
            if (e.getMessage() instanceof HttpResponse) {
                response = (HttpResponse) e.getMessage();

                log.debug("\n\nRequest {}\n\nResponse {}\n", nettyRequest, response);

                // Required if there is some trailing headers.
                future.setHttpResponse(response);

                int statusCode = response.getStatus().getCode();

                String ka = response.getHeader(HttpHeaders.Names.CONNECTION);
                future.setKeepAlive(ka == null || ka.toLowerCase().equals("keep-alive"));

                List<String> wwwAuth = getWwwAuth(response.getHeaders());
                Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

                HttpResponseStatus status = new ResponseStatus(future.getURI(), response, this);
                FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(request).responseStatus(status).build();
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

                // The request has changed
                if (fc.replayRequest()) {
                    replayRequest(future, fc, response, ctx);
                    return;
                }

                if (statusCode == 401
                        && wwwAuth.size() > 0
                        && realm != null
                        && !future.getAndSetAuth(true)) {

                    final RequestBuilder builder = new RequestBuilder(future.getRequest());
                    future.setState(NettyResponseFuture.STATE.NEW);

                    if (!future.getURI().getPath().equalsIgnoreCase(realm.getUri())) {
                        builder.setUrl(future.getURI().toString());
                    }

                    Realm newRealm = null;
                    final FluentCaseInsensitiveStringsMap headers = request.getHeaders();
                    ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();

                    // TODO: Refactor and put this code out of here.
                    // NTLM
                    if (wwwAuth.get(0).startsWith("NTLM") || (wwwAuth.get(0).startsWith("Negotiate")
                            && realm.getAuthScheme() == Realm.AuthScheme.NTLM)) {

                        String ntlmDomain = proxyServer == null ? realm.getNtlmDomain() : proxyServer.getNtlmDomain();
                        String ntlmHost = proxyServer == null ? realm.getNtlmHost() : proxyServer.getHost();
                        String prinicipal = proxyServer == null ? realm.getPrincipal() : proxyServer.getPrincipal();
                        String password = proxyServer == null ? realm.getPassword() : proxyServer.getPassword();
                        if (!realm.isNtlmMessageType2Received()) {
                            String challengeHeader = ntlmEngine.generateType1Msg(ntlmDomain, ntlmHost);

                            headers.add(HttpHeaders.Names.AUTHORIZATION, "NTLM " + challengeHeader);

                            newRealm = new Realm.RealmBuilder().clone(realm)
                                    .setScheme(realm.getAuthScheme())
                                    .setUri(URI.create(request.getUrl()).getPath())
                                    .setMethodName(request.getMethod())
                                    .setNtlmMessageType2Received(true)
                                    .build();
                            future.getAndSetAuth(false);
                        } else {
                            String serverChallenge = wwwAuth.get(0).trim().substring("NTLM ".length());
                            String challengeHeader = ntlmEngine.generateType3Msg(prinicipal, password,
                                    ntlmDomain, ntlmHost, serverChallenge);

                            headers.remove(HttpHeaders.Names.AUTHORIZATION);
                            headers.add(HttpHeaders.Names.AUTHORIZATION, "NTLM " + challengeHeader);

                            newRealm = new Realm.RealmBuilder().clone(realm)
                                    .setScheme(realm.getAuthScheme())
                                    .setUri(URI.create(request.getUrl()).getPath())
                                    .setMethodName(request.getMethod())
                                    .build();
                        }
                        // SPNEGO KERBEROS
                    } else if (wwwAuth.get(0).startsWith("Negotiate") && (realm.getAuthScheme() == Realm.AuthScheme.KERBEROS
                            || realm.getAuthScheme() == Realm.AuthScheme.SPNEGO)) {

                        URI uri = URI.create(request.getUrl());
                        String authServer = proxyServer == null ? AsyncHttpProviderUtils.getBaseUrl(uri) : proxyServer.getHost();
                        try {
                            String challengeHeader = spnegoEngine.generateToken(authServer);
                            headers.remove(HttpHeaders.Names.AUTHORIZATION);
                            headers.add(HttpHeaders.Names.AUTHORIZATION, "Negociate " + challengeHeader);

                            newRealm = new Realm.RealmBuilder().clone(realm)
                                    .setScheme(realm.getAuthScheme())
                                    .setUri(uri.getPath())
                                    .setMethodName(request.getMethod())
                                    .build();
                        } catch (Throwable throwable) {
                            abort(future, throwable);
                            return;
                        }
                    } else {
                        newRealm = new Realm.RealmBuilder().clone(realm)
                                .setScheme(realm.getAuthScheme())
                                .setUri(URI.create(request.getUrl()).getPath())
                                .setMethodName(request.getMethod())
                                .setUsePreemptiveAuth(true)
                                .parseWWWAuthenticateHeader(wwwAuth.get(0))
                                .build();
                    }

                    final Realm nr = newRealm;

                    log.debug("Sending authentication to {}", request.getUrl());

                    if (future.getKeepAlive()) {
                        future.attachChannel(ctx.getChannel(), true);
                    }

                    nextRequest(builder.setHeaders(headers).setRealm(nr).build(), future);
                    return;
                }

                if (statusCode == 100) {
                    future.getAndSetWriteHeaders(false);
                    future.getAndSetWriteBody(true);
                    writeRequest(ctx.getChannel(), config, future, nettyRequest);
                    return;
                }

                String proxyAuth = response.getHeader(HttpHeaders.Names.PROXY_AUTHENTICATE);
                if (statusCode == 407
                        && proxyAuth != null
                        && future.getRequest().getRealm() != null
                        && !future.getAndSetAuth(true)) {

                    log.debug("Sending proxy authentication to {}", request.getUrl());

                    nextRequest(future.getRequest(), future);
                    return;
                }

                if (future.getNettyRequest().getMethod().equals(HttpMethod.CONNECT)
                        && statusCode == 200) {

                    ProxyServer proxyServer = request.getProxyServer() != null ? request.getProxyServer() : config.getProxyServer();
                    log.debug("Connected to {}:{}", proxyServer.getHost(), proxyServer.getPort());

                    if (future.getKeepAlive()) {
                        future.attachChannel(ctx.getChannel(), true);
                    }

                    final RequestBuilder builder = new RequestBuilder(future.getRequest());
                    try {
                        upgradeProtocol(ctx.getChannel().getPipeline(), request.getUrl(), proxyServer);
                    } catch (Throwable ex) {
                        abort(future, ex);
                    }

                    nextRequest(builder.build(), future);
                    return;
                }

                boolean redirectEnabled = request.isRedirectEnabled() ? true : config.isRedirectEnabled();
                if (redirectEnabled && (statusCode == 302 || statusCode == 301)) {

                    if (future.incrementAndGetCurrentRedirectCount() < config.getMaxRedirects()) {
                        // We must allow 401 handling again.
                        future.getAndSetAuth(false);

                        String location = response.getHeader(HttpHeaders.Names.LOCATION);
                        URI uri = AsyncHttpProviderUtils.getRedirectUri(future.getURI(), location);

                        if (!uri.toString().equalsIgnoreCase(future.getURI().toString())) {
                            final RequestBuilder builder = new RequestBuilder(future.getRequest());
                            final URI initialConnectionUri = future.getURI();
                            future.setURI(uri);
                            final String newUrl = uri.toString();

                            log.debug("Redirecting to {}", newUrl);

                            if (response.isChunked()) {
                                drainChannel(ctx, future, initialConnectionUri);
                            } else {
                                closeChannel(ctx);
                            }

                            nextRequest(builder.setUrl(newUrl).build(), future);
                            return;
                        }
                    } else {
                        throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
                    }
                }

                if (!future.getAndSetStatusReceived(true) && updateStatusAndInterrupt(handler, status)) {
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
                    updateBodyAndInterrupt(handler, new ResponseBodyPart(future.getURI(), response, this));
                    markAsDoneAndCacheConnection(future, ctx);
                    drainChannel(ctx, future, future.getURI());
                    return;
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
            if (IOException.class.isAssignableFrom(t.getClass()) && config.getIOExceptionFilters().size() > 0) {
                FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler())
                        .request(future.getRequest()).ioException(IOException.class.cast(t)).build();
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

    private void drainChannel(final ChannelHandlerContext ctx, final NettyResponseFuture<?> future, final URI uri){
        ctx.setAttachment(new AsyncCallable(future) {
            public Object call() throws Exception {
                if (future.getKeepAlive() && ctx.getChannel().isReadable()) {
                    if (!connectionsPool.offer(AsyncHttpProviderUtils.getBaseUrl(uri), ctx.getChannel())) {
                        finishChannel(ctx);
                    }
                } else {
                    finishChannel(ctx);
                }
                return null;
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
        drainChannel(ctx, future, future.getURI());
        nextRequest(newRequest, future);
        return;
    }

    private List<String> getWwwAuth(List<Entry<String, String>> list) {
        ArrayList<String> l = new ArrayList<String>();
        for (Entry<String, String> e : list) {
            if (e.getKey().equalsIgnoreCase("WWW-Authenticate")) {
                l.add(e.getValue());
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

    private void abort(NettyResponseFuture<?> future, Throwable t) {
        if (trackConnections && future.channel() != null && openChannels.contains(future.channel())) {
            maxConnections.decrementAndGet();
            openChannels.remove(future.channel());
        }

        log.debug("Aborting Future {}\n", future);
        log.debug(t.getMessage(), t);

        future.abort(t);
    }

    private void upgradeProtocol(ChannelPipeline p, String scheme, ProxyServer proxyServer) throws IOException, GeneralSecurityException {
        if (p.get(HTTP_HANDLER) != null) {
            p.remove(HTTP_HANDLER);
        }

        log.debug("Connecting to proxy {} for scheme {}", proxyServer, scheme);

        if (scheme.startsWith(HTTPS)) {
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

        if (isClose.get()) {
            return;
        }

        connectionsPool.removeAll(ctx.getChannel());

        Exception exception = null;
        try {
            super.channelClosed(ctx, e);
        } catch (Exception ex) {
            exception = ex;
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

            if (config.getIOExceptionFilters().size() > 0) {
                FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler())
                        .request(future.getRequest()).ioException(new IOException("Channel Closed")).build();
                fc = handleIoException(fc, future);

                if (fc.replayRequest() && !future.cannotBeReplay()) {
                    replayRequest(future, fc, null, ctx);
                    return;
                }
            }

            if (future != null && !future.isDone()) {
                if (!remotelyClosed(ctx.getChannel(), future)) {
                    abort(future, new IOException("Remotely Closed"));
                }
            }
        } else {
            closeChannel(ctx);
        }
    }

    protected boolean remotelyClosed(Channel channel, NettyResponseFuture<?> future) {

        if (isClose.get()) {
            return false;
        }

        connectionsPool.removeAll(channel);

        if (future == null && channel.getPipeline().getContext(NettyAsyncHttpProvider.class).getAttachment() != null
                && NettyResponseFuture.class.isAssignableFrom(
                channel.getPipeline().getContext(NettyAsyncHttpProvider.class).getAttachment().getClass())) {
            future = (NettyResponseFuture<?>)
                    channel.getPipeline().getContext(NettyAsyncHttpProvider.class).getAttachment();
        }

        if (future == null || future.cannotBeReplay()) {
            log.debug("Unable to recover future {}\n", future);
            return false;
        }

        future.setState(NettyResponseFuture.STATE.RECONNECTED);

        log.debug("Trying to recover request {}\n", future.getNettyRequest());

        try {
            nextRequest(future.getRequest(), future);
            return true;
        } catch (IOException iox) {
            future.setState(NettyResponseFuture.STATE.CLOSED);
            future.abort(iox);
            log.error("Remotely Closed, unable to recover", iox);
        }
        return false;
    }

    private void markAsDoneAndCacheConnection(final NettyResponseFuture<?> future, final ChannelHandlerContext ctx) throws MalformedURLException {
        // We need to make sure everything is OK before adding the connection back to the pool.
        try {
            future.done(null);
        } catch (Throwable t) {
            // Never propagate exception once we know we are done.
            log.debug(t.getMessage(), t);
        }

        if (!future.getKeepAlive()) {
            closeChannel(ctx);
        }
    }

    private void finishUpdate(final NettyResponseFuture<?> future, final ChannelHandlerContext ctx, boolean isChunked) throws IOException {
        if (isChunked && future.getKeepAlive()) {
            drainChannel(ctx, future, future.getURI());
        } else {
             if (future.getKeepAlive() && ctx.getChannel().isReadable()) {
                if (!connectionsPool.offer(AsyncHttpProviderUtils.getBaseUrl(future.getURI()), ctx.getChannel())) {
                    finishChannel(ctx);
                }
            }
        }
        markAsDoneAndCacheConnection(future, ctx);
    }

    private boolean markChannelNotReadable(final ChannelHandlerContext ctx) {
        // Catch any unexpected exception when marking the channel.
        ctx.setAttachment(new DiscardEvent());
        return true;
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

        if (log.isDebugEnabled()) {
            log.debug("exceptionCaught", cause);
        }

        try {

            if (cause != null && ClosedChannelException.class.isAssignableFrom(cause.getClass())) {
                return;
            }

            if (ctx.getAttachment() instanceof NettyResponseFuture<?>) {
                future = (NettyResponseFuture<?>) ctx.getAttachment();
                future.attachChannel(null, false);
                future.touch();

                if (IOException.class.isAssignableFrom(cause.getClass())){

                    if (config.getIOExceptionFilters().size() > 0) {
                        FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler())
                                .request(future.getRequest()).ioException(new IOException("Channel Closed")).build();
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
        closeChannel(ctx);
        ctx.sendUpstream(e);
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
        int length = (int) request.getContentLength();
        if (length == -1 && r.getHeader(HttpHeaders.Names.CONTENT_LENGTH) != null) {
            length = Integer.valueOf(r.getHeader(HttpHeaders.Names.CONTENT_LENGTH));
        }

        if (length >= 0) {
            r.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(length));
        }
        return length;
    }

    public static <T> NettyResponseFuture<T> newFuture(URI uri,
                                                       Request request,
                                                       AsyncHandler<T> asyncHandler,
                                                       HttpRequest nettyRequest,
                                                       AsyncHttpClientConfig config,
                                                       NettyAsyncHttpProvider provider) {

        NettyResponseFuture<T> f = new NettyResponseFuture<T>(uri, request, asyncHandler, nettyRequest,
                requestTimeout(config, request.getPerRequestConfig()), provider);

        if (request.getHeaders().getFirstValue("Expect") != null
                && request.getHeaders().getFirstValue("Expect").equalsIgnoreCase("100-Continue")) {
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

                if (IllegalStateException.class.isAssignableFrom(cause.getClass())) {
                    log.debug(cause.getMessage(), cause);
                    try {
                        cf.getChannel().close();
                    } catch (RuntimeException ex) {
                        log.debug(ex.getMessage(), ex);
                    }
                    return;
                }

                if (ClosedChannelException.class.isAssignableFrom(cause.getClass())
                        || abortOnReadCloseException(cause)
                        || abortOnWriteCloseException(cause)) {

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
             * We need to make sure we aren't in the middle of an authorization process before publishing events
             * as we will re-publish again the same event after the authorization, causing unpredictable behavior.
             */
            Realm realm = future.getRequest().getRealm() != null ? future.getRequest().getRealm() : NettyAsyncHttpProvider.this.getConfig().getRealm();
            boolean startPublishing = future.isInAuth()
                    || realm == null
                    || realm.getUsePreemptiveAuth() == true;

            if (startPublishing && ProgressAsyncHandler.class.isAssignableFrom(asyncHandler.getClass())) {
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

    /**
     * Because some implementation of the ThreadSchedulingService do not clean up cancel task until they try to run
     * them, we wrap the task with the future so the when the NettyResponseFuture cancel the reaper future
     * this wrapper will release the references to the channel and the nettyResponseFuture immediately. Otherwise,
     * the memory referenced this way will only be released after the request timeout period which can be arbitrary long.
     */
    private final class ReaperFuture implements Future, Runnable {
        private Future scheduledFuture;
        private Channel channel;
        private NettyResponseFuture<?> nettyResponseFuture;

        public ReaperFuture(Channel channel, NettyResponseFuture<?> nettyResponseFuture) {
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
            if (isClose.get()) {
                return;
            }

            if (this.nettyResponseFuture != null && this.nettyResponseFuture.hasExpired()) {
                log.debug("Request Timeout expired for {}\n", this.nettyResponseFuture);

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
                throw new IllegalArgumentException(
                        "position out of range: " + position +
                                " (expected: 0 - " + (this.count - 1) + ")");
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
}

