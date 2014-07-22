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
package com.ning.http.client.providers.netty.request;

import static com.ning.http.util.AsyncHttpProviderUtils.DEFAULT_CHARSET;
import static com.ning.http.util.AsyncHttpProviderUtils.getNonEmptyPath;
import static com.ning.http.util.MiscUtils.isNonEmpty;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.FileRegion;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedFile;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.Timer;
import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHandlerExtensions;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;
import com.ning.http.client.ConnectionPoolKeyStrategy;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.RandomAccessBody;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.cookie.CookieEncoder;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.generators.InputStreamBodyGenerator;
import com.ning.http.client.listener.TransferCompletionHandler;
import com.ning.http.client.ntlm.NTLMEngine;
import com.ning.http.client.ntlm.NTLMEngineException;
import com.ning.http.client.providers.netty.Callback;
import com.ning.http.client.providers.netty.NettyAsyncHttpProvider;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.request.body.BodyChunkedInput;
import com.ning.http.client.providers.netty.request.body.BodyFileRegion;
import com.ning.http.client.providers.netty.request.body.OptimizedFileRegion;
import com.ning.http.client.providers.netty.request.timeout.ReadTimeoutTimerTask;
import com.ning.http.client.providers.netty.request.timeout.RequestTimeoutTimerTask;
import com.ning.http.client.providers.netty.request.timeout.TimeoutsHolder;
import com.ning.http.client.providers.netty.spnego.SpnegoEngine;
import com.ning.http.client.providers.netty.util.HttpUtil;
import com.ning.http.client.providers.netty.ws.WebSocketUtil;
import com.ning.http.client.uri.UriComponents;
import com.ning.http.multipart.MultipartBody;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.AuthenticatorUtils;
import com.ning.http.util.ProxyUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class NettyRequestSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyRequestSender.class);
    public static final String GZIP_DEFLATE = HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE;

    private final AsyncHttpClientConfig config;
    private final NettyAsyncHttpProviderConfig nettyConfig;
    private final ChannelManager channelManager;
    private final Timer nettyTimer;
    private final AtomicBoolean closed;
    private final boolean disableZeroCopy;

    public NettyRequestSender(AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig, ChannelManager channelManager,
            Timer nettyTimer, AtomicBoolean closed) {
        this.config = config;
        this.nettyConfig = nettyConfig;
        this.channelManager = channelManager;
        this.nettyTimer = nettyTimer;
        this.closed = closed;
        disableZeroCopy = nettyConfig.isDisableZeroCopy();
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

    public Timeout newTimeout(TimerTask task, long delay) {
        return nettyTimer.newTimeout(task, delay, TimeUnit.MILLISECONDS);
    }

    public final <T> void writeRequest(final Channel channel, final AsyncHttpClientConfig config, final NettyResponseFuture<T> future) {

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

                    channel.write(nettyRequest).addListener(new ProgressListener(config, true, future.getAsyncHandler(), future));
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
                                writeFuture = channel.write(new ChunkedFile(raf, 0, raf.length(), nettyConfig.getChunkedFileChunkSize()));
                            } else {
                                final FileRegion region = new OptimizedFileRegion(raf, 0, raf.length());
                                writeFuture = channel.write(region);
                            }
                            writeFuture.addListener(new ProgressListener(config, false, future.getAsyncHandler(), future) {
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
                        writeFuture.addListener(new ProgressListener(config, false, future.getAsyncHandler(), future) {
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
            int requestTimeout = AsyncHttpProviderUtils.requestTimeout(config, future.getRequest());
            TimeoutsHolder timeoutsHolder = new TimeoutsHolder();
            if (requestTimeout != -1) {
                timeoutsHolder.requestTimeout = newTimeout(new RequestTimeoutTimerTask(future, this, timeoutsHolder, requestTimeout),
                        requestTimeout);
            }

            int readTimeout = config.getReadTimeout();
            if (readTimeout != -1 && readTimeout <= requestTimeout) {
                // no need for a idleConnectionTimeout that's less than the requestTimeout
                timeoutsHolder.readTimeout = newTimeout(
                        new ReadTimeoutTimerTask(future, this, timeoutsHolder, requestTimeout, readTimeout), readTimeout);
            }
            future.setTimeoutsHolder(timeoutsHolder);

        } catch (RejectedExecutionException ex) {
            abort(future, ex);
        }
    }

    public void nextRequest(final Request request, final NettyResponseFuture<?> future) throws IOException {
        nextRequest(request, future, true);
    }

    private void nextRequest(final Request request, final NettyResponseFuture<?> future, final boolean useCache) throws IOException {
        execute(request, future, useCache, true);
    }

    private <T> void execute(final Request request, final NettyResponseFuture<T> f, boolean useCache, boolean reclaimCache)
            throws IOException {
        doConnect(request, f.getAsyncHandler(), f, useCache, reclaimCache);
    }

    private Channel lookupInCache(UriComponents uri, ProxyServer proxy, ConnectionPoolKeyStrategy strategy) {
        final Channel channel = channelManager.poll(channelManager.getPoolKey(uri, proxy, strategy));

        if (channel != null) {
            LOGGER.debug("Using cached Channel {}\n for uri {}\n", channel, uri);

            try {
                // Always make sure the channel who got cached support the proper protocol. It could
                // only occurs when a HttpMethod.CONNECT is used against a proxy that requires upgrading from http to
                // https.
                return channelManager.verifyChannelPipeline(channel, uri.getScheme());
            } catch (Exception ex) {
                LOGGER.debug(ex.getMessage(), ex);
            }
        }
        return null;
    }

    private <T> NettyResponseFuture<T> newFuture(UriComponents uri, Request request, AsyncHandler<T> asyncHandler,
            HttpRequest nettyRequest, AsyncHttpClientConfig config, ProxyServer proxyServer) {

        NettyResponseFuture<T> f = new NettyResponseFuture<T>(uri,//
                request,//
                asyncHandler,//
                nettyRequest,//
                config.getMaxRequestRetry(),//
                request.getConnectionPoolKeyStrategy(),//
                proxyServer);

        String expectHeader = request.getHeaders().getFirstValue(HttpHeaders.Names.EXPECT);
        if (expectHeader != null && expectHeader.equalsIgnoreCase(HttpHeaders.Values.CONTINUE)) {
            f.getAndSetWriteBody(false);
        }
        return f;
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
                    f = newFuture(uri, request, asyncHandler, nettyRequest, config, proxyServer);
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

    private String computeNonConnectRequestPath(AsyncHttpClientConfig config, UriComponents uri, ProxyServer proxyServer) {
        if (proxyServer != null && !(HttpUtil.isSecure(uri) && config.isUseRelativeURIsWithSSLProxies()))
            return uri.toString();
        else {
            String path = getNonEmptyPath(uri);
            return uri.getQuery() != null ? path + "?" + uri.getQuery() : path;
        }
    }

    private HttpRequest construct(AsyncHttpClientConfig config, Request request, HttpMethod m, UriComponents uri, ChannelBuffer buffer,
            ProxyServer proxyServer) throws IOException {

        HttpRequest nettyRequest;

        if (m.equals(HttpMethod.CONNECT)) {
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_0, m, AsyncHttpProviderUtils.getAuthority(uri));
        } else {
            String path = computeNonConnectRequestPath(config, uri, proxyServer);
            nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, m, path);
        }

        HttpHeaders nettyRequestHeaders = nettyRequest.headers();

        boolean webSocket = HttpUtil.isWebSocket(uri.getScheme());
        if (webSocket && !m.equals(HttpMethod.CONNECT)) {
            nettyRequestHeaders.add(HttpHeaders.Names.UPGRADE, HttpHeaders.Values.WEBSOCKET);
            nettyRequestHeaders.add(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.UPGRADE);
            nettyRequestHeaders.add(HttpHeaders.Names.ORIGIN, "http://" + uri.getHost() + ":" + uri.getPort());
            nettyRequestHeaders.add(HttpHeaders.Names.SEC_WEBSOCKET_KEY, WebSocketUtil.getKey());
            nettyRequestHeaders.add(HttpHeaders.Names.SEC_WEBSOCKET_VERSION, "13");
        }

        String host = request.getVirtualHost() != null ? request.getVirtualHost() : uri.getHost();
        String hostHeader = request.getVirtualHost() != null || uri.getPort() == -1 ? host : host + ":" + uri.getPort();
        nettyRequestHeaders.set(HttpHeaders.Names.HOST, hostHeader);

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
            if (HttpUtil.isNTLM(auth)) {
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
                    nettyRequestHeaders.add(HttpHeaders.Names.AUTHORIZATION, NTLMEngine.INSTANCE.generateType1Msg("NTLM " + domain, authHost));
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
                    challengeHeader = SpnegoEngine.INSTANCE.generateToken(server);
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
                    if (!HttpUtil.isNTLM(auth)) {
                        try {
                            String msg = NTLMEngine.INSTANCE.generateType1Msg(proxyServer.getNtlmDomain(), proxyServer.getHost());
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

            Charset bodyCharset = request.getBodyEncoding() == null ? DEFAULT_CHARSET : Charset.forName(request.getBodyEncoding());

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

    private final HttpRequest buildRequest(AsyncHttpClientConfig config, Request request, UriComponents uri, boolean allowConnect,
            ChannelBuffer buffer, ProxyServer proxyServer) throws IOException {

        String method = request.getMethod();
        if (allowConnect && proxyServer != null && HttpUtil.isSecure(uri)) {
            method = HttpMethod.CONNECT.toString();
        }
        return construct(config, request, new HttpMethod(method), uri, buffer, proxyServer);
    }

    private <T> NettyResponseFuture<T> buildConnectListenerFuture(AsyncHttpClientConfig config,//
            Request request,//
            AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//
            ChannelBuffer buffer,//
            UriComponents uri) throws IOException {
        ProxyServer proxyServer = ProxyUtils.getProxyServer(config, request);
        HttpRequest nettyRequest = buildRequest(config, request, uri, true, buffer, proxyServer);
        if (future == null) {
            return newFuture(uri, request, asyncHandler, nettyRequest, config, proxyServer);
        } else {
            future.setNettyRequest(nettyRequest);
            future.setRequest(request);
            return future;
        }
    }

    public <T> ListenableFuture<T> doConnect(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> f,
            boolean useCache, boolean reclaimCache) throws IOException {

        if (isClosed()) {
            throw new IOException("Closed");
        }

        UriComponents uri = request.getURI();

        if (uri.getScheme().startsWith(HttpUtil.WEBSOCKET) && !channelManager.validateWebSocketRequest(request, asyncHandler))
            throw new IOException("WebSocket method must be a GET");

        ProxyServer proxyServer = ProxyUtils.getProxyServer(config, request);

        boolean resultOfAConnect = f != null && f.getNettyRequest() != null && f.getNettyRequest().getMethod().equals(HttpMethod.CONNECT);
        boolean useProxy = proxyServer != null && !resultOfAConnect;

        ChannelBuffer bufferedBytes = null;
        if (f != null && f.getRequest().getFile() == null
                && !f.getNettyRequest().getMethod().getName().equals(HttpMethod.CONNECT.getName())) {
            bufferedBytes = f.getNettyRequest().getContent();
        }

        boolean useSSl = HttpUtil.isSecure(uri) && !useProxy;

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

        NettyResponseFuture<T> connectListenerFuture = buildConnectListenerFuture(config, request, asyncHandler, f, bufferedBytes, uri);

        boolean channelPreempted = false;
        String poolKey = null;

        // Do not throw an exception when we need an extra connection for a redirect.
        if (!reclaimCache) {

            // only compute when maxConnectionPerHost is enabled
            // FIXME clean up
            if (config.getMaxConnectionsPerHost() > 0)
                poolKey = channelManager.getPoolKey(connectListenerFuture);

            if (channelManager.preemptChannel(poolKey)) {
                channelPreempted = true;
            } else {
                IOException ex = new IOException(String.format("Too many connections %s", config.getMaxConnections()));
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

        ChannelFuture channelFuture;
        ClientBootstrap bootstrap = channelManager.getBootstrap(request.getURI().getScheme(), useProxy, useSSl);

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

    public boolean retry(Channel channel, NettyResponseFuture<?> future) {

        if (isClosed())
            return false;

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
                return true;

            } catch (IOException iox) {
                future.setState(NettyResponseFuture.STATE.CLOSED);
                future.abort(iox);
                LOGGER.error("Remotely Closed, unable to recover", iox);
                return false;
            }

        } else {
            LOGGER.debug("Unable to recover future {}\n", future);
            return false;
        }
    }

    public final Callback newDrainCallable(final NettyResponseFuture<?> future, final Channel channel, final boolean keepAlive,
            final String poolKey) {

        return new Callback(future) {
            public void call() throws Exception {
                channelManager.tryToOfferChannelToPool(channel, keepAlive, poolKey);
            }
        };
    }

    public void drainChannel(final Channel channel, final NettyResponseFuture<?> future) {
        Channels.setAttachment(channel, newDrainCallable(future, channel, future.isKeepAlive(), channelManager.getPoolKey(future)));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, Channel channel) throws IOException {
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

    public boolean isClosed() {
        return closed.get();
    }
}
