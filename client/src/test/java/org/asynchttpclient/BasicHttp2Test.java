/*
 *    Copyright (c) 2014-2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient;

import io.netty.bootstrap.ServerBootstrap;
import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.NameResolver;
import io.netty.util.ReferenceCountUtil;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.asynchttpclient.handler.MaxRedirectException;
import org.asynchttpclient.handler.RedirectRefusedException;
import org.asynchttpclient.netty.channel.ConnectionSemaphore;
import org.asynchttpclient.netty.channel.ConnectionSemaphoreFactory;
import org.asynchttpclient.netty.channel.DefaultConnectionSemaphoreFactory;
import org.asynchttpclient.netty.request.NettyRequest;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.asynchttpclient.test.EventCollectingHandler;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.asynchttpclient.test.TestUtils.AsyncCompletionHandlerAdapter;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.asynchttpclient.test.TestUtils.createTempFile;
import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;
import static org.asynchttpclient.util.ThrowableUtil.unknownStackTrace;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for HTTP/2 support using a self-contained Netty-based HTTP/2 test server.
 * <p>
 * The embedded server uses {@link Http2FrameCodecBuilder} and {@link Http2MultiplexHandler} on
 * the server side, and tests verify that the client correctly:
 * <ul>
 *   <li>Negotiates HTTP/2 via ALPN</li>
 *   <li>Sends requests as HTTP/2 frames ({@link Http2HeadersFrame} + {@link Http2DataFrame})</li>
 *   <li>Receives responses and delivers them via the normal {@link AsyncHandler} callback sequence</li>
 *   <li>Correctly multiplexes concurrent requests over a single connection</li>
 *   <li>Falls back to HTTP/1.1 when HTTP/2 is disabled</li>
 * </ul>
 */
@ExtendWith(NettyLeakDetectorExtension.class)
public class BasicHttp2Test {

    // Event constants (from HttpTest/EventCollectingHandler)
    private static final String COMPLETED_EVENT = "Completed";
    private static final String STATUS_RECEIVED_EVENT = "StatusReceived";
    private static final String HEADERS_RECEIVED_EVENT = "HeadersReceived";
    private static final String HEADERS_WRITTEN_EVENT = "HeadersWritten";
    private static final String CONNECTION_OPEN_EVENT = "ConnectionOpen";
    private static final String HOSTNAME_RESOLUTION_EVENT = "HostnameResolution";
    private static final String HOSTNAME_RESOLUTION_SUCCESS_EVENT = "HostnameResolutionSuccess";
    private static final String CONNECTION_SUCCESS_EVENT = "ConnectionSuccess";
    private static final String TLS_HANDSHAKE_EVENT = "TlsHandshake";
    private static final String TLS_HANDSHAKE_SUCCESS_EVENT = "TlsHandshakeSuccess";
    private static final String CONNECTION_POOL_EVENT = "ConnectionPool";
    private static final String CONNECTION_OFFER_EVENT = "ConnectionOffer";
    private static final String REQUEST_SEND_EVENT = "RequestSend";

    private NioEventLoopGroup serverGroup;
    private Channel serverChannel;
    private ChannelGroup serverChildChannels;
    private SslContext serverSslCtx;
    private int serverPort;
    // Per client connection, under the address the server sees it from: whether GOAWAY arrived before it closed.
    private static final BlockingQueue<Runnable> HELD_RESPONSES = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<SocketAddress, CompletableFuture<Boolean>> goAwayBeforeCloseByClient =
            new ConcurrentHashMap<>();

    /**
     * Path-routing HTTP/2 server handler that supports multiple test scenarios.
     */
    private static final class Http2TestServerHandler extends SimpleChannelInboundHandler<Object> {
        private Http2Headers requestHeaders;
        private final List<ByteBuf> bodyChunks = new ArrayList<>();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame) {
                Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                this.requestHeaders = headersFrame.headers();
                if (headersFrame.isEndStream()) {
                    routeRequest(ctx, Unpooled.EMPTY_BUFFER);
                }
            } else if (msg instanceof Http2DataFrame) {
                Http2DataFrame dataFrame = (Http2DataFrame) msg;
                bodyChunks.add(dataFrame.content().retain());
                if (dataFrame.isEndStream()) {
                    int totalBytes = bodyChunks.stream().mapToInt(ByteBuf::readableBytes).sum();
                    ByteBuf combined = ctx.alloc().buffer(totalBytes);
                    bodyChunks.forEach(chunk -> {
                        combined.writeBytes(chunk);
                        chunk.release();
                    });
                    bodyChunks.clear();
                    routeRequest(ctx, combined);
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            releaseBodyChunks();
            super.channelInactive(ctx);
        }

        private void releaseBodyChunks() {
            for (ByteBuf chunk : bodyChunks) {
                if (chunk.refCnt() > 0) {
                    chunk.release();
                }
            }
            bodyChunks.clear();
        }

        private void routeRequest(ChannelHandlerContext ctx, ByteBuf body) {
            String path = requestHeaders.path() != null ? requestHeaders.path().toString() : "/";
            String method = requestHeaders.method() != null ? requestHeaders.method().toString() : "GET";

            // Strip query string for routing
            String queryString = null;
            int qIdx = path.indexOf('?');
            String routePath = path;
            if (qIdx >= 0) {
                queryString = path.substring(qIdx + 1);
                routePath = path.substring(0, qIdx);
            }

            if (routePath.equals("/ok")) {
                ReferenceCountUtil.safeRelease(body);
                sendSimpleResponse(ctx, "200", Unpooled.EMPTY_BUFFER, null);
            } else if (routePath.startsWith("/status/")) {
                String statusCode = routePath.substring("/status/".length());
                ReferenceCountUtil.safeRelease(body);
                sendSimpleResponse(ctx, statusCode, Unpooled.EMPTY_BUFFER, null);
            } else if (routePath.startsWith("/delay/")) {
                long millis = Long.parseLong(routePath.substring("/delay/".length()));
                ReferenceCountUtil.safeRelease(body);
                ctx.executor().schedule(() -> {
                    if (ctx.channel().isActive()) {
                        sendSimpleResponse(ctx, "200", Unpooled.EMPTY_BUFFER, null);
                    }
                }, millis, TimeUnit.MILLISECONDS);
            } else if (routePath.startsWith("/redirect/")) {
                int count = Integer.parseInt(routePath.substring("/redirect/".length()));
                ReferenceCountUtil.safeRelease(body);
                Http2Headers responseHeaders = new DefaultHttp2Headers().status("302");
                if (count > 0) {
                    responseHeaders.add("location", "/redirect/" + (count - 1));
                } else {
                    responseHeaders.status("200");
                }
                ctx.write(new DefaultHttp2HeadersFrame(responseHeaders, true));
                ctx.flush();
            } else if (routePath.equals("/downgrade")) {
                ReferenceCountUtil.safeRelease(body);
                Http2Headers responseHeaders = new DefaultHttp2Headers().status("302");
                responseHeaders.add("location", "http://127.0.0.1:1/target");
                ctx.write(new DefaultHttp2HeadersFrame(responseHeaders, true));
                ctx.flush();
            } else if (routePath.equals("/cross-origin")) {
                ReferenceCountUtil.safeRelease(body);
                Http2Headers responseHeaders = new DefaultHttp2Headers().status("307");
                responseHeaders.add("location", "https://127.0.0.1:1/target");
                ctx.write(new DefaultHttp2HeadersFrame(responseHeaders, true));
                ctx.flush();
            } else if (routePath.equals("/head")) {
                ReferenceCountUtil.safeRelease(body);
                Http2Headers responseHeaders = new DefaultHttp2Headers()
                        .status("200")
                        .add(HttpHeaderNames.CONTENT_LENGTH, "100");
                if ("HEAD".equalsIgnoreCase(method)) {
                    ctx.write(new DefaultHttp2HeadersFrame(responseHeaders, true));
                    ctx.flush();
                } else {
                    sendSimpleResponse(ctx, "200", Unpooled.EMPTY_BUFFER, null);
                }
            } else if (routePath.equals("/options")) {
                ReferenceCountUtil.safeRelease(body);
                Http2Headers responseHeaders = new DefaultHttp2Headers()
                        .status("200")
                        .add("allow", "GET,HEAD,POST,OPTIONS,TRACE");
                ctx.write(new DefaultHttp2HeadersFrame(responseHeaders, true));
                ctx.flush();
            } else if (routePath.equals("/cookies")) {
                ReferenceCountUtil.safeRelease(body);
                Http2Headers responseHeaders = new DefaultHttp2Headers().status("200");
                CharSequence cookieHeader = requestHeaders.get("cookie");
                if (cookieHeader != null) {
                    String[] cookies = cookieHeader.toString().split(";\\s*");
                    for (String cookie : cookies) {
                        responseHeaders.add("set-cookie", cookie.trim());
                    }
                }
                ctx.write(new DefaultHttp2HeadersFrame(responseHeaders, true));
                ctx.flush();
            } else if (routePath.equals("/held")) {
                // Answered only when the test runs the task this adds to HELD_RESPONSES.
                ReferenceCountUtil.safeRelease(body);
                HELD_RESPONSES.add(() -> ctx.executor().execute(
                        () -> sendSimpleResponse(ctx, "200", Unpooled.EMPTY_BUFFER, null)));
            } else if (routePath.equals("/reset")) {
                ReferenceCountUtil.safeRelease(body);
                ctx.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.INTERNAL_ERROR));
            } else {
                // Default: echo handler — takes ownership of body via writeResponse
                sendEchoResponse(ctx, body, path, routePath, queryString, method);
            }
        }

        private void sendEchoResponse(ChannelHandlerContext ctx, ByteBuf body, String fullPath,
                                       String routePath, String queryString, String method) {
            Http2Headers responseHeaders = new DefaultHttp2Headers().status("200");

            // Echo Content-Type
            if (requestHeaders.get(CONTENT_TYPE) != null) {
                responseHeaders.add(CONTENT_TYPE, requestHeaders.get(CONTENT_TYPE));
            }

            // Echo path info
            responseHeaders.add("x-pathinfo", routePath);

            // Echo query string
            if (queryString != null) {
                responseHeaders.add("x-querystring", queryString);
            }

            // Echo request headers as X-{name}, and also report the exact (case-preserving) set of
            // received non-pseudo header names in a single value so wire casing / exclusion is testable.
            StringBuilder receivedNames = new StringBuilder();
            for (Map.Entry<CharSequence, CharSequence> entry : requestHeaders) {
                String name = entry.getKey().toString();
                // Skip pseudo-headers
                if (!name.startsWith(":")) {
                    responseHeaders.add("x-" + name, entry.getValue());
                    if (receivedNames.length() > 0) {
                        receivedNames.append(',');
                    }
                    receivedNames.append(name);
                }
            }
            responseHeaders.add("x-received-names", receivedNames.toString());

            // Handle OPTIONS
            if ("OPTIONS".equalsIgnoreCase(method)) {
                responseHeaders.add("allow", "GET,HEAD,POST,OPTIONS,TRACE");
            }

            // Parse form parameters from body if content-type is form-urlencoded
            CharSequence contentType = requestHeaders.get(CONTENT_TYPE);
            if (contentType != null && contentType.toString().contains("application/x-www-form-urlencoded")
                    && body.isReadable()) {
                String bodyStr = body.toString(UTF_8);
                QueryStringDecoder decoder = new QueryStringDecoder("?" + bodyStr);
                for (Map.Entry<String, List<String>> entry : decoder.parameters().entrySet()) {
                    String value = entry.getValue().get(0);
                    responseHeaders.add("x-" + entry.getKey(),
                            URLEncoder.encode(value, UTF_8));
                }
            }

            // Handle cookies
            CharSequence cookieHeader = requestHeaders.get("cookie");
            if (cookieHeader != null) {
                String[] cookies = cookieHeader.toString().split(";\\s*");
                for (String cookie : cookies) {
                    responseHeaders.add("set-cookie", cookie.trim());
                }
            }

            responseHeaders.add(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(body.readableBytes()));
            writeResponse(ctx, responseHeaders, body);
        }

        private void sendSimpleResponse(ChannelHandlerContext ctx, String status, ByteBuf body,
                                         Map<String, String> extraHeaders) {
            Http2Headers responseHeaders = new DefaultHttp2Headers()
                    .status(status)
                    .add(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(body.readableBytes()));
            if (extraHeaders != null) {
                extraHeaders.forEach(responseHeaders::add);
            }
            writeResponse(ctx, responseHeaders, body);
        }

        private void writeResponse(ChannelHandlerContext ctx, Http2Headers responseHeaders, ByteBuf body) {
            boolean hasBody = body.isReadable();
            ctx.write(new DefaultHttp2HeadersFrame(responseHeaders, !hasBody));
            if (hasBody) {
                ctx.writeAndFlush(new DefaultHttp2DataFrame(body, true)).addListener(f -> {
                    if (!f.isSuccess() && body.refCnt() > 0) {
                        body.release();
                    }
                });
            } else {
                ctx.flush();
                ReferenceCountUtil.safeRelease(body);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    @BeforeEach
    public void startServer() throws Exception {
        X509Bundle bundle = new CertificateBuilder()
                .subject("CN=localhost")
                .setIsCertificateAuthority(true)
                .buildSelfSigned();

        serverSslCtx = SslContextBuilder.forServer(bundle.toKeyManagerFactory())
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2,
                        ApplicationProtocolNames.HTTP_1_1))
                .build();

        serverGroup = new NioEventLoopGroup(1);
        serverChildChannels = new DefaultChannelGroup("http2-test-server", GlobalEventExecutor.INSTANCE);

        ServerBootstrap b = new ServerBootstrap()
                .group(serverGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        serverChildChannels.add(ch);
                        ch.pipeline()
                                .addLast("ssl", serverSslCtx.newHandler(ch.alloc()))
                                .addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
                                    @Override
                                    protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                                        if (!ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                                            throw new IllegalStateException("unexpected ALPN protocol: " + protocol);
                                        }
                                        ctx.pipeline()
                                                .addLast(Http2FrameCodecBuilder.forServer().build())
                                                .addLast(new CloseObserver())
                                                .addLast(new Http2MultiplexHandler(
                                                        new ChannelInitializer<Http2StreamChannel>() {
                                                            @Override
                                                            protected void initChannel(Http2StreamChannel streamCh) {
                                                                streamCh.pipeline()
                                                                        .addLast(new Http2TestServerHandler());
                                                            }
                                                        }));
                                    }
                                });
                    }
                });

        serverChannel = b.bind(0).sync().channel();
        serverPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @AfterEach
    public void stopServer() throws InterruptedException {
        HELD_RESPONSES.clear();
        if (serverChildChannels != null) {
            serverChildChannels.close().sync();
        }
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        if (serverGroup != null) {
            serverGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).sync();
        }
        ReferenceCountUtil.release(serverSslCtx);
    }

    private @Nullable Channel negotiatedHttp2Channel() {
        for (Channel child : serverChildChannels) {
            if (child.pipeline().get(Http2FrameCodec.class) != null) {
                return child;
            }
        }
        return null;
    }

    /**
     * Records, on the server side, whether a connection sent GOAWAY before it closed, as RFC 9113 section 6.8 asks of
     * a connection that is closed on purpose.
     */
    private final class CloseObserver extends ChannelInboundHandlerAdapter {
        private SocketAddress clientAddress;
        private boolean goAwayReceived;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            clientAddress = ctx.channel().remoteAddress();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2GoAwayFrame) {
                goAwayReceived = true;
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            goAwayBeforeClose(clientAddress).complete(goAwayReceived);
            ctx.fireChannelInactive();
        }
    }

    private CompletableFuture<Boolean> goAwayBeforeClose(SocketAddress clientAddress) {
        return goAwayBeforeCloseByClient.computeIfAbsent(clientAddress, address -> new CompletableFuture<>());
    }

    /**
     * Fails unless the connection, closed by now, sent GOAWAY first. The address is the client's local one, read while
     * the connection was still open.
     */
    private void assertClosedWithGoAway(SocketAddress clientAddress) throws Exception {
        assertTrue(goAwayBeforeClose(clientAddress).get(10, SECONDS),
                "the connection must send GOAWAY before it closes, not just close");
    }

    private String httpsUrl(String path) {
        return "https://localhost:" + serverPort + path;
    }

    /**
     * Creates an AHC client configured to trust self-signed certs (for testing) with HTTP/2 enabled.
     */
    private AsyncHttpClient http2Client() {
        return asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true));
    }

    /**
     * Creates an AHC client with HTTP/2 disabled (forced HTTP/1.1 fallback).
     */
    private AsyncHttpClient http1Client() {
        return asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(false));
    }

    /**
     * Creates an AHC client with custom config + trust manager + HTTP/2.
     */
    private AsyncHttpClient http2ClientWithConfig(Consumer<DefaultAsyncHttpClientConfig.Builder> customizer) {
        DefaultAsyncHttpClientConfig.Builder builder = config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true);
        customizer.accept(builder);
        return asyncHttpClient(builder);
    }

    @Test
    public void httpsRequestsThroughHttpProxyNegotiateAndReuseHttp2AfterConnect() throws Exception {
        assertHttpsRequestsThroughProxyNegotiateAndReuseHttp2AfterConnect(false);
    }

    @Test
    public void httpsRequestsThroughHttpsProxyNegotiateAndReuseHttp2AfterConnect() throws Exception {
        assertHttpsRequestsThroughProxyNegotiateAndReuseHttp2AfterConnect(true);
    }

    private void assertHttpsRequestsThroughProxyNegotiateAndReuseHttp2AfterConnect(boolean secureProxy) throws Exception {
        Server proxy = new Server();
        ServerConnector proxyConnector = secureProxy ? addHttpsConnector(proxy) : addHttpConnector(proxy);
        proxy.setHandler(new ConnectHandler());

        try {
            proxy.start();
            try (AsyncHttpClient client = http2Client()) {
                ProxyServer.Builder proxyBuilder = proxyServer("127.0.0.1", proxyConnector.getLocalPort());
                if (secureProxy) {
                    proxyBuilder.setProxyType(ProxyType.HTTPS);
                }
                ProxyServer proxyServer = proxyBuilder.build();
                Response firstResponse = client.prepareGet(httpsUrl("/hello"))
                        .setProxyServer(proxyServer)
                        .execute()
                        .get(30, SECONDS);
                Response secondResponse = client.prepareGet(httpsUrl("/hello"))
                        .setProxyServer(proxyServer)
                        .execute()
                        .get(30, SECONDS);

                assertNotNull(firstResponse);
                assertEquals(200, firstResponse.getStatusCode());
                assertEquals(HttpProtocol.HTTP_2, firstResponse.getProtocol());
                assertNotNull(secondResponse);
                assertEquals(200, secondResponse.getStatusCode());
                assertEquals(HttpProtocol.HTTP_2, secondResponse.getProtocol());
                assertEquals(1, serverChildChannels.size(),
                        "the HTTP/2 tunnel connection should be registered and reused");
            }
        } finally {
            proxy.stop();
        }
    }

    /**
     * With {@link LoadBalance#ROUND_ROBIN}, a host resolving to several IPs gets one HTTP/2
     * connection per IP (the registry is keyed by the IP-aware partition key), so requests are spread
     * across all of them instead of all multiplexing onto a single connection. SNI/cert verification
     * still use the URL host ("localhost"), so only the connection target IP varies.
     */
    @Test
    public void http2RoundRobinSpreadsConnectionsAcrossIps() throws Exception {
        final List<InetAddress> ips = new ArrayList<>();
        for (String ip : new String[]{"127.0.0.1", "127.0.0.2", "127.0.0.3"}) {
            ips.add(InetAddress.getByName(ip));
        }
        NameResolver<InetAddress> resolver =
                new InetNameResolver(ImmediateEventExecutor.INSTANCE) {
                    @Override
                    protected void doResolve(String inetHost, Promise<InetAddress> promise) {
                        promise.setSuccess(ips.get(0));
                    }

                    @Override
                    protected void doResolveAll(String inetHost, Promise<List<InetAddress>> promise) {
                        promise.setSuccess(new ArrayList<>(ips));
                    }
                };

        // Assert on the targeted IP (onTcpConnectAttempt), not the connected IP: on macOS only 127.0.0.1
        // is a usable loopback address, so the others are targeted but fail over. With an IP-aware H2
        // registry each distinct IP still triggers its own connection attempt.
        Set<String> attemptedIps = ConcurrentHashMap.newKeySet();
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setLoadBalance(LoadBalance.ROUND_ROBIN).setMaxRequestRetry(0))) {
            for (int i = 0; i < 12; i++) {
                Response response = client.executeRequest(
                        Dsl.get(httpsUrl("/hello")).setNameResolver(resolver),
                        new AsyncCompletionHandler<Response>() {
                            @Override
                            public void onTcpConnectAttempt(InetSocketAddress remoteAddress) {
                                if (remoteAddress.getAddress() != null) {
                                    attemptedIps.add(remoteAddress.getAddress().getHostAddress());
                                }
                            }

                            @Override
                            public Response onCompleted(Response response) {
                                return response;
                            }
                        }).get(30, SECONDS);
                assertEquals(200, response.getStatusCode());
            }
        }
        assertEquals(Set.of("127.0.0.1", "127.0.0.2", "127.0.0.3"), attemptedIps,
                "round-robin should target every resolved IP over HTTP/2 (each gets its own connection)");
    }

    /**
     * A fixed multi-IP resolver for a single hostname (mirrors the inline resolver used above):
     * {@code doResolve} returns the first IP, {@code doResolveAll} returns the full list.
     */
    private static NameResolver<InetAddress> multiIpResolver(String... ips) throws Exception {
        final List<InetAddress> addresses = new ArrayList<>();
        for (String ip : ips) {
            addresses.add(InetAddress.getByName(ip));
        }
        return new InetNameResolver(ImmediateEventExecutor.INSTANCE) {
            @Override
            protected void doResolve(String inetHost, Promise<InetAddress> promise) {
                promise.setSuccess(addresses.get(0));
            }

            @Override
            protected void doResolveAll(String inetHost, Promise<List<InetAddress>> promise) {
                promise.setSuccess(new ArrayList<>(addresses));
            }
        };
    }

    /**
     * Only 127.0.0.1 is usable on the loopback interface of macOS. A connect to another 127.x address waits out the
     * connect timeout there and fails over to 127.0.0.1, where the failed-IP cooldown then pins the next requests too.
     */
    private static void assumeLoopbackAddressIsUsable(String ip) throws Exception {
        boolean usable;
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(InetAddress.getByName(ip), 0));
            usable = true;
        } catch (IOException notOnThisHost) {
            usable = false;
        }
        assumeTrue(usable, ip + " is not a usable loopback address on this host");
    }

    /**
     * Regression guard for the round-robin sibling-reuse fix (issue #2214): when {@code maxConnectionsPerHost}
     * is at least the number of resolved IPs, no request is ever permit-starved, so the sibling-reuse fallback
     * must NOT engage and round-robin must still open one connection per IP. If sibling reuse leaked onto the
     * happy path it would collapse all requests onto a single connection and this would fail.
     */
    @Test
    public void http2RoundRobinStillSpreadsWhenPermitsAbundant() throws Exception {
        NameResolver<InetAddress> resolver = multiIpResolver("127.0.0.1", "127.0.0.2", "127.0.0.3");
        Set<String> attemptedIps = ConcurrentHashMap.newKeySet();
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setLoadBalance(LoadBalance.ROUND_ROBIN)
                .setMaxConnectionsPerHost(3).setMaxRequestRetry(0))) {
            for (int i = 0; i < 12; i++) {
                Response response = client.executeRequest(
                        Dsl.get(httpsUrl("/ok")).setNameResolver(resolver),
                        new AsyncCompletionHandler<Response>() {
                            @Override
                            public void onTcpConnectAttempt(InetSocketAddress remoteAddress) {
                                if (remoteAddress.getAddress() != null) {
                                    attemptedIps.add(remoteAddress.getAddress().getHostAddress());
                                }
                            }

                            @Override
                            public Response onCompleted(Response response) {
                                return response;
                            }
                        }).get(30, SECONDS);
                assertEquals(200, response.getStatusCode());
            }
        }
        assertEquals(Set.of("127.0.0.1", "127.0.0.2", "127.0.0.3"), attemptedIps,
                "with enough permits the sibling fallback must not engage — round-robin still targets every IP");
    }

    /**
     * Issue #2214: round-robin pins each request to one of the host's IPs, but the connection permit is per
     * host. With {@code maxConnectionsPerHost=1} and a multi-IP host, a request pinned to an IP whose
     * connection is not open and that cannot acquire the per-host permit must multiplex onto an HTTP/2
     * connection already open to a sibling IP, instead of stalling for {@code connectTimeout} and failing
     * with {@code TooManyConnectionsPerHostException}. We fire a burst of concurrent requests so several are
     * permit-starved while the first connection is being established; with the fix they all complete by
     * reusing the sibling connection. {@code connectTimeout} is kept short so a regression surfaces as a fast
     * failure rather than a long hang.
     */
    @Test
    public void http2RoundRobinPermitStarvedReusesSiblingConnection() throws Exception {
        NameResolver<InetAddress> resolver = multiIpResolver("127.0.0.1", "127.0.0.2");
        int concurrentRequests = 8;
        CountDownLatch latch = new CountDownLatch(concurrentRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setLoadBalance(LoadBalance.ROUND_ROBIN)
                .setMaxConnectionsPerHost(1).setMaxRequestRetry(0).setConnectTimeout(Duration.ofSeconds(2)))) {
            for (int i = 0; i < concurrentRequests; i++) {
                client.executeRequest(
                        Dsl.get(httpsUrl("/delay/300")).setNameResolver(resolver),
                        new AsyncCompletionHandlerBase() {
                            @Override
                            public Response onCompleted(Response response) {
                                if (response.getStatusCode() == 200) {
                                    successCount.incrementAndGet();
                                }
                                latch.countDown();
                                return response;
                            }

                            @Override
                            public void onThrowable(Throwable t) {
                                firstError.compareAndSet(null, t);
                                latch.countDown();
                            }
                        });
            }
            assertTrue(latch.await(30, SECONDS), "all round-robin requests should complete");
            assertNull(firstError.get(),
                    "permit-starved requests must reuse a sibling-IP HTTP/2 connection, not fail; got: " + firstError.get());
            assertEquals(concurrentRequests, successCount.get(),
                    "all requests should succeed via sibling-IP HTTP/2 reuse under maxConnectionsPerHost=1");
        }
    }

    /**
     * In round-robin mode the per-host connection permit is held for the HTTP/2 connection's lifetime, so
     * {@code maxConnectionsPerHost} actually caps the number of live connections to a host. With a host of
     * three IPs and a cap of two, round-robin would otherwise open three connections (one per IP); the cap
     * must hold it to at most two, with requests pinned to the third IP multiplexing onto a sibling
     * connection (issue #2214). All requests still succeed.
     */
    @Test
    public void http2RoundRobinCapsLiveConnectionsAtMaxConnectionsPerHost() throws Exception {
        NameResolver<InetAddress> resolver = multiIpResolver("127.0.0.1", "127.0.0.2", "127.0.0.3");
        int maxPerHost = 2;
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setLoadBalance(LoadBalance.ROUND_ROBIN)
                .setMaxConnectionsPerHost(maxPerHost).setMaxRequestRetry(0))) {
            for (int i = 0; i < 12; i++) {
                Response response = client.executeRequest(
                        Dsl.get(httpsUrl("/ok")).setNameResolver(resolver)).get(30, SECONDS);
                assertEquals(200, response.getStatusCode());
            }
            // serverChildChannels tracks accepted TCP (HTTP/2) connections; a DefaultChannelGroup auto-removes
            // closed ones. Without holding the permit for the connection lifetime, round-robin opens one
            // connection per resolved IP (3 > cap); the cap must hold live connections to at most maxPerHost.
            assertTrue(serverChildChannels.size() <= maxPerHost,
                    "maxConnectionsPerHost must cap live HTTP/2 connections; expected <= " + maxPerHost
                            + ", got " + serverChildChannels.size());
        }
    }

    /**
     * Issue #2214 drain-permit fix: with {@code maxConnectionsPerHost=1} a single live round-robin
     * connection saturates the per-host cap, so its permit must be released when the server's GOAWAY starts
     * draining it, not when it finally closes. The first request is a long-running stream that keeps the
     * connection open across the drain (the GOAWAY carries a high lastStreamId so the stream survives it,
     * and the request is never awaited); the second request must then open a replacement connection promptly
     * instead of failing with {@code TooManyConnectionsPerHostException} after stalling for
     * {@code connectTimeout}.
     */
    @Test
    public void http2RoundRobinGoawayReleasesPermitForReplacementConnection() throws Exception {
        NameResolver<InetAddress> resolver = multiIpResolver("127.0.0.1", "127.0.0.2");
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setLoadBalance(LoadBalance.ROUND_ROBIN)
                .setMaxConnectionsPerHost(1).setMaxRequestRetry(0)
                .setConnectTimeout(Duration.ofSeconds(1)).setRequestTimeout(Duration.ofSeconds(60)))) {

            // Long-running stream that keeps its connection (and, before the fix, the only permit) busy.
            client.executeRequest(Dsl.get(httpsUrl("/delay/30000")).setNameResolver(resolver));

            // Wait for a connection that has settled on HTTP/2; one that has only been accepted has no
            // codec yet and cannot encode the GOAWAY below.
            long deadline = System.currentTimeMillis() + 5000;
            Channel parent = negotiatedHttp2Channel();
            while (parent == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
                parent = negotiatedHttp2Channel();
            }
            assertNotNull(parent, "no HTTP/2 connection settled within 5s");
            assertEquals(1, serverChildChannels.size(), "exactly one HTTP/2 connection should be established");

            // GOAWAY with a high lastStreamId leaves the in-flight stream running, so the connection
            // stays open and draining.
            parent.writeAndFlush(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR)
                    .setExtraStreamIds(1000)).sync();
            Thread.sleep(300);

            // Must open a replacement connection; before the fix this failed with
            // TooManyConnectionsPerHostException because the draining connection still held the permit.
            Response replacement = client.executeRequest(
                    Dsl.get(httpsUrl("/ok")).setNameResolver(resolver)).get(10, SECONDS);
            assertEquals(200, replacement.getStatusCode(),
                    "a request after GOAWAY must open a replacement connection, not fail with "
                            + "TooManyConnectionsPerHostException while the draining connection pins the permit");
        }
    }

    /**
     * Records which connection an exchange ran on: {@link #opened} when it opened one, {@link #pooled} when it
     * reused one, and when it was written.
     */
    private static final class ConnectionRecorder extends AsyncCompletionHandlerBase {

        private final AtomicReference<Channel> opened = new AtomicReference<>();
        private final AtomicReference<Channel> pooled = new AtomicReference<>();
        private final CountDownLatch sent = new CountDownLatch(1);

        @Override
        public void onTcpConnectSuccess(InetSocketAddress remoteAddress, Channel connection) {
            opened.set(connection);
        }

        @Override
        public void onConnectionPooled(Channel connection) {
            pooled.set(connection);
        }

        @Override
        public void onRequestSend(NettyRequest request) {
            sent.countDown();
        }

        private Channel connection() {
            Channel channel = opened.get() != null ? opened.get() : pooled.get();
            assertNotNull(channel, "the exchange ran on no connection");
            return channel;
        }
    }

    /**
     * Issue #2370: an HTTP/2 connection is retired once it reaches {@code connectionTtl}. Idle, it closes at that
     * point, and the next request opens a new connection.
     */
    @Test
    public void http2ConnectionIsReplacedAfterConnectionTtl() throws Exception {
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setConnectionTtl(Duration.ofMillis(300)))) {
            ConnectionRecorder first = new ConnectionRecorder();
            Response firstResponse = client.prepareGet(httpsUrl("/ok")).execute(first).get(10, SECONDS);
            assertEquals(HttpProtocol.HTTP_2, firstResponse.getProtocol());
            Channel firstConnection = first.connection();
            SocketAddress firstAddress = firstConnection.localAddress();

            assertTrue(firstConnection.closeFuture().await(10, SECONDS),
                    "an idle HTTP/2 connection must close by itself once it reaches connectionTtl");
            assertClosedWithGoAway(firstAddress);

            ConnectionRecorder second = new ConnectionRecorder();
            Response secondResponse = client.prepareGet(httpsUrl("/ok")).execute(second).get(10, SECONDS);
            assertEquals(200, secondResponse.getStatusCode());
            assertEquals(HttpProtocol.HTTP_2, secondResponse.getProtocol());
            assertNotNull(second.opened.get(), "the next request must open a new connection");
            assertNotSame(firstConnection, second.connection());
        }
    }

    /**
     * Issue #2370: a stream in flight when its connection reaches {@code connectionTtl} finishes normally on it,
     * while requests sent after the expiry already go to a new connection; the retired one closes once the stream
     * ends.
     */
    @Test
    public void http2StreamInFlightAtConnectionTtlCompletesWhileNewRequestsMove() throws Exception {
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setConnectionTtl(Duration.ofSeconds(1)))) {
            ConnectionRecorder longRunning = new ConnectionRecorder();
            ListenableFuture<Response> longResponse = client.prepareGet(httpsUrl("/delay/3000")).execute(longRunning);
            assertTrue(longRunning.sent.await(10, SECONDS));
            Channel retiring = longRunning.connection();
            SocketAddress retiringAddress = retiring.localAddress();

            // Sequential requests ride the connection until it is retired; the first one after that opens another.
            // Each round trip paces the loop, which the long-running stream's 3 s bounds well past the 1 s TTL.
            ConnectionRecorder moved = null;
            long deadline = System.nanoTime() + SECONDS.toNanos(10);
            while (moved == null && System.nanoTime() < deadline) {
                ConnectionRecorder next = new ConnectionRecorder();
                client.prepareGet(httpsUrl("/ok")).execute(next).get(10, SECONDS);
                if (next.connection() != retiring) {
                    moved = next;
                }
            }
            assertNotNull(moved, "requests sent after connectionTtl must move to a new connection");
            assertNotNull(moved.opened.get(), "by opening one");
            assertFalse(longResponse.isDone(), "the long-running stream is still in flight on the retired connection");
            assertTrue(retiring.isActive(), "and the retired connection stays open for it");

            assertEquals(200, longResponse.get(10, SECONDS).getStatusCode(),
                    "a stream in flight at connectionTtl must complete normally");
            assertTrue(retiring.closeFuture().await(10, SECONDS),
                    "the retired connection must close once its last stream has ended");
            assertClosedWithGoAway(retiringAddress);
        }
    }

    /**
     * Issue #2370: a request queued for a stream slot when its connection reaches {@code connectionTtl} is served on
     * that connection once a slot frees up, rather than being failed.
     */
    @Test
    public void http2RequestQueuedAtConnectionTtlIsServedOnTheRetiredConnection() throws Exception {
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setConnectionTtl(Duration.ofSeconds(1))
                .setHttp2MaxConcurrentStreams(1))) {
            ConnectionRecorder holder = new ConnectionRecorder();
            ListenableFuture<Response> holderResponse = client.prepareGet(httpsUrl("/delay/3000")).execute(holder);
            assertTrue(holder.sent.await(10, SECONDS));
            Channel connection = holder.connection();
            SocketAddress connectionAddress = connection.localAddress();

            // The only slot is held for 3 s, so this waits in the queue across the 1 s expiry.
            ConnectionRecorder queued = new ConnectionRecorder();
            ListenableFuture<Response> queuedResponse = client.prepareGet(httpsUrl("/ok")).execute(queued);
            assertSame(connection, queued.pooled.get(), "queued on the connection the long stream holds");
            assertEquals(1, queued.sent.getCount(), "and not yet sent, since there is no free slot");

            assertEquals(200, queuedResponse.get(10, SECONDS).getStatusCode(),
                    "a request queued at connectionTtl must be served, not failed");
            assertEquals(200, holderResponse.get(10, SECONDS).getStatusCode());
            assertNull(queued.opened.get(), "it was served on the retired connection, not on a new one");
            assertTrue(connection.closeFuture().await(10, SECONDS),
                    "the retired connection must close once both exchanges are done");
            assertClosedWithGoAway(connectionAddress);
        }
    }

    /**
     * Holds an exchange between its pool lookup and its write, for as long as the connection it was handed for the
     * first time is open, and counts retries.
     */
    private static final class IdleCloseRacer extends AsyncCompletionHandlerBase {

        private final Channel heldUntilClosed;
        private final AtomicInteger retries;
        private final AtomicBoolean pooledOnIt = new AtomicBoolean();

        private IdleCloseRacer(Channel heldUntilClosed, AtomicInteger retries) {
            this.heldUntilClosed = heldUntilClosed;
            this.retries = retries;
        }

        @Override
        public void onConnectionPooled(Channel connection) {
            if (connection == heldUntilClosed) {
                pooledOnIt.set(true);
                connection.closeFuture().awaitUninterruptibly(10, SECONDS);
            }
        }

        @Override
        public void onRetry() {
            retries.incrementAndGet();
        }
    }

    /**
     * Issue #2370: requests that looked the connection up just before it reached {@code connectionTtl}, and reach
     * their write only after it was closed idle, are sent on a new connection. Nothing was written, so they are
     * neither failed nor retried, and none is left waiting for its request timeout.
     */
    @Test
    public void http2RequestsRacingTheIdleCloseAtConnectionTtlAreSentOnANewConnection() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setConnectionTtl(Duration.ofSeconds(1))
                .setMaxRequestRetry(0))) {
            ConnectionRecorder first = new ConnectionRecorder();
            client.prepareGet(httpsUrl("/ok")).execute(first).get(10, SECONDS);
            Channel idle = first.connection();
            AtomicInteger retries = new AtomicInteger();

            // Two, because only the first request to find the closed connection can take its one active token, and the
            // closed-channel path completes nothing for any later one.
            List<IdleCloseRacer> racers = List.of(new IdleCloseRacer(idle, retries), new IdleCloseRacer(idle, retries));
            List<Future<ListenableFuture<Response>>> submitted = new ArrayList<>();
            for (IdleCloseRacer racer : racers) {
                submitted.add(callers.submit(() -> client.prepareGet(httpsUrl("/ok")).execute(racer)));
            }
            for (Future<ListenableFuture<Response>> request : submitted) {
                assertEquals(200, request.get(10, SECONDS).get(10, SECONDS).getStatusCode());
            }

            for (IdleCloseRacer racer : racers) {
                assertTrue(racer.pooledOnIt.get(),
                        "each request must have been handed the connection before it closed");
            }
            assertFalse(idle.isOpen());
            assertEquals(0, retries.get(), "nothing was sent, so nothing may count as a retry");
        } finally {
            callers.shutdownNow();
        }
    }

    /**
     * Issue #2370 with {@link LoadBalance#ROUND_ROBIN}: each per-IP connection is retired on its own, and replaced.
     */
    @Test
    public void http2RoundRobinConnectionsAreEachReplacedAfterConnectionTtl() throws Exception {
        assumeLoopbackAddressIsUsable("127.0.0.2");
        NameResolver<InetAddress> resolver = multiIpResolver("127.0.0.1", "127.0.0.2");
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setLoadBalance(LoadBalance.ROUND_ROBIN)
                .setConnectionTtl(Duration.ofMillis(300)))) {
            List<Channel> firstConnections = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ConnectionRecorder recorder = new ConnectionRecorder();
                client.executeRequest(Dsl.get(httpsUrl("/ok")).setNameResolver(resolver), recorder).get(10, SECONDS);
                firstConnections.add(recorder.connection());
            }
            assertNotSame(firstConnections.get(0), firstConnections.get(1), "one connection per IP");

            for (Channel connection : firstConnections) {
                assertTrue(connection.closeFuture().await(10, SECONDS),
                        "every per-IP connection must be retired at connectionTtl");
            }
            for (int i = 0; i < 2; i++) {
                ConnectionRecorder recorder = new ConnectionRecorder();
                Response response = client.executeRequest(Dsl.get(httpsUrl("/ok")).setNameResolver(resolver), recorder)
                        .get(10, SECONDS);
                assertEquals(200, response.getStatusCode());
                assertNotNull(recorder.opened.get(), "each IP gets a new connection");
            }
        }
    }

    /**
     * {@link LoadBalance#LEAST_CONNECTIONS} only chooses where a new connection goes, so a multi-IP host still gets a
     * single HTTP/2 connection that every request multiplexes onto.
     */
    @Test
    public void http2LeastConnectionsMultiplexesOntoASingleConnection() throws Exception {
        NameResolver<InetAddress> resolver = multiIpResolver("127.0.0.1", "127.0.0.2", "127.0.0.3");
        List<String> attemptedIps = new CopyOnWriteArrayList<>();
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setLoadBalance(LoadBalance.LEAST_CONNECTIONS)
                .setMaxRequestRetry(0))) {
            for (int i = 0; i < 12; i++) {
                Response response = client.executeRequest(Dsl.get(httpsUrl("/ok")).setNameResolver(resolver),
                        new AsyncCompletionHandlerBase() {
                            @Override
                            public void onTcpConnectAttempt(InetSocketAddress remoteAddress) {
                                attemptedIps.add(remoteAddress.getAddress().getHostAddress());
                            }
                        }).get(30, SECONDS);
                assertEquals(200, response.getStatusCode());
                assertEquals(HttpProtocol.HTTP_2, response.getProtocol());
            }
            assertEquals(1, serverChildChannels.size(), "a single connection is open");
        }
        assertEquals(List.of("127.0.0.1"), attemptedIps, "one connection, to the first address, for all twelve requests");
    }

    /**
     * With {@link LoadBalance#LEAST_CONNECTIONS}, an HTTP/2 connection retired at {@code connectionTtl} still counts while
     * its last stream finishes, so the connection that replaces it goes to another address.
     */
    @Test
    public void http2LeastConnectionsSendsTheReplacementOfARetiringConnectionElsewhere() throws Exception {
        assumeLoopbackAddressIsUsable("127.0.0.2");
        NameResolver<InetAddress> resolver = multiIpResolver("127.0.0.1", "127.0.0.2");
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setLoadBalance(LoadBalance.LEAST_CONNECTIONS)
                .setConnectionTtl(Duration.ofSeconds(1)).setMaxRequestRetry(0))) {
            ConnectionRecorder longRunning = new ConnectionRecorder();
            ListenableFuture<Response> longResponse = client.executeRequest(
                    Dsl.get(httpsUrl("/held")).setNameResolver(resolver), longRunning);
            Runnable answer = HELD_RESPONSES.poll(10, SECONDS);
            assertNotNull(answer, "the server never got the request it is to hold");
            Channel retiring = longRunning.connection();
            assertEquals("127.0.0.1", ipOf(retiring));

            // Sequential requests ride the connection until it is retired; the first one after that opens another.
            ConnectionRecorder moved = null;
            long deadline = System.nanoTime() + SECONDS.toNanos(10);
            while (moved == null && System.nanoTime() < deadline) {
                ConnectionRecorder next = new ConnectionRecorder();
                client.executeRequest(Dsl.get(httpsUrl("/ok")).setNameResolver(resolver), next).get(10, SECONDS);
                if (next.connection() != retiring) {
                    moved = next;
                }
            }
            assertNotNull(moved, "requests sent after connectionTtl must move to a new connection");
            assertNotNull(moved.opened.get(), "by opening one");
            assertTrue(retiring.isActive(), "the retired connection is still open for its held stream");
            assertEquals("127.0.0.2", ipOf(moved.connection()), "the retiring connection still counts against 127.0.0.1");
            answer.run();
            assertEquals(200, longResponse.get(10, SECONDS).getStatusCode());
        }
    }

    /**
     * Requests that race the first HTTP/2 connection each open their own, and {@link LoadBalance#LEAST_CONNECTIONS}
     * spreads those over the addresses. One stays registered; the others close after their own request and stop
     * counting. How many race depends on timing, so this checks only the state the burst settles into.
     */
    @Test
    public void http2LeastConnectionsBurstSettlesOnOneConnectionWithNothingElseCounted() throws Exception {
        assumeLoopbackAddressIsUsable("127.0.0.2");
        assumeLoopbackAddressIsUsable("127.0.0.3");
        List<String> ips = List.of("127.0.0.1", "127.0.0.2", "127.0.0.3");
        NameResolver<InetAddress> resolver = multiIpResolver(ips.toArray(new String[0]));
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setLoadBalance(LoadBalance.LEAST_CONNECTIONS)
                .setMaxRequestRetry(0))) {
            List<ConnectionRecorder> burst = new ArrayList<>();
            List<ListenableFuture<Response>> responses = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                ConnectionRecorder recorder = new ConnectionRecorder();
                burst.add(recorder);
                responses.add(client.executeRequest(Dsl.get(httpsUrl("/ok")).setNameResolver(resolver), recorder));
            }
            for (ListenableFuture<Response> response : responses) {
                assertEquals(200, response.get(30, SECONDS).getStatusCode());
            }

            ConnectionRecorder after = new ConnectionRecorder();
            client.executeRequest(Dsl.get(httpsUrl("/ok")).setNameResolver(resolver), after).get(30, SECONDS);
            assertNull(after.opened.get(), "a later request multiplexes onto the registered connection");
            Channel kept = after.connection();
            for (ConnectionRecorder recorder : burst) {
                Channel opened = recorder.opened.get();
                if (opened != null && opened != kept) {
                    assertTrue(opened.closeFuture().await(10, SECONDS), "a connection that lost the race closes");
                    // Its count is released by a close listener, on its event loop.
                    opened.eventLoop().submit(() -> {
                    }).get(10, SECONDS);
                }
            }
            assertEquals(List.of(kept), registeredHttp2Connections(client));
            for (String ip : ips) {
                assertEquals(ip.equals(ipOf(kept)) ? 1 : 0, leastConnectionsCount(client, ip), "count of " + ip);
            }
        }
    }

    private static String ipOf(Channel connection) {
        return ((InetSocketAddress) connection.remoteAddress()).getAddress().getHostAddress();
    }

    private static List<Channel> registeredHttp2Connections(AsyncHttpClient client) throws Exception {
        Object channelManager = ((DefaultAsyncHttpClient) client).channelManager();
        Field registry = channelManager.getClass().getDeclaredField("http2Connections");
        registry.setAccessible(true);
        List<Channel> connections = new ArrayList<>();
        for (Object byKey : ((Map<?, ?>) registry.get(channelManager)).values()) {
            for (Object connection : ((Map<?, ?>) byKey).values()) {
                connections.add((Channel) connection);
            }
        }
        return connections;
    }

    // The current LEAST_CONNECTIONS count for ip at the test server's port.
    private int leastConnectionsCount(AsyncHttpClient client, String ip) throws Exception {
        Field senderField = DefaultAsyncHttpClient.class.getDeclaredField("requestSender");
        senderField.setAccessible(true);
        Object sender = senderField.get(client);
        Field selectorField = sender.getClass().getDeclaredField("leastConnections");
        selectorField.setAccessible(true);
        Object selector = selectorField.get(sender);
        Method count = selector.getClass().getDeclaredMethod("activeConnections", InetSocketAddress.class);
        count.setAccessible(true);
        return (int) count.invoke(selector, new InetSocketAddress(InetAddress.getByName(ip), serverPort));
    }

    /**
     * Issue #2370 with {@link LoadBalance#ROUND_ROBIN} and {@code maxConnectionsPerHost} 1: a request refused the
     * per-host permit while the only connection held it, and finding that connection retired and gone when it looks
     * for one to multiplex onto, takes the permit the retirement freed and opens the replacement. It used to wait for a
     * connection to register, which nothing would do, and fail after connectTimeout with
     * {@code TooManyConnectionsPerHostException}.
     */
    @Test
    public void http2RoundRobinRequestRefusedThePermitAsItsConnectionRetiresOpensTheReplacement() throws Exception {
        assumeLoopbackAddressIsUsable("127.0.0.2");
        permitRefusedAsTheOnlyConnectionGoesAwayOpensTheReplacement(Duration.ofSeconds(1), parent -> {
        });
    }

    /**
     * The same with a server GOAWAY taking the only connection away, which frees its permit the same way.
     */
    @Test
    public void http2RoundRobinRequestRefusedThePermitAtGoawayOpensTheReplacement() throws Exception {
        assumeLoopbackAddressIsUsable("127.0.0.2");
        permitRefusedAsTheOnlyConnectionGoesAwayOpensTheReplacement(Duration.ofMillis(-1),
                parent -> parent.writeAndFlush(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR)));
    }

    /**
     * One connection holds the only permit. A second request, pinned to the other IP, is refused the permit and held
     * right there until that connection is gone, taken away by {@code takeAway} on its server side or by
     * {@code connectionTtl}, so that it looks for a connection to multiplex onto only once there is none. It must then
     * open one of its own.
     */
    private void permitRefusedAsTheOnlyConnectionGoesAwayOpensTheReplacement(Duration connectionTtl,
            Consumer<Channel> takeAway) throws Exception {
        NameResolver<InetAddress> resolver = multiIpResolver("127.0.0.1", "127.0.0.2");
        CompletableFuture<Channel> onlyConnection = new CompletableFuture<>();
        CountDownLatch refused = new CountDownLatch(1);
        ConnectionSemaphoreFactory holdingTheRefused = clientConfig -> {
            ConnectionSemaphore permits = new DefaultConnectionSemaphoreFactory().newConnectionSemaphore(clientConfig);
            return new ConnectionSemaphore() {
                @Override
                public void acquireChannelLock(Object partitionKey) throws IOException {
                    acquireChannelLock(partitionKey, false);
                }

                @Override
                public void acquireChannelLock(Object partitionKey, boolean nonBlocking) throws IOException {
                    try {
                        permits.acquireChannelLock(partitionKey, nonBlocking);
                    } catch (IOException noPermit) {
                        refused.countDown();
                        onlyConnection.join().closeFuture().awaitUninterruptibly(10, SECONDS);
                        throw noPermit;
                    }
                }

                @Override
                public void releaseChannelLock(Object partitionKey) {
                    permits.releaseChannelLock(partitionKey);
                }
            };
        };
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setLoadBalance(LoadBalance.ROUND_ROBIN)
                .setMaxConnectionsPerHost(1).setMaxRequestRetry(0).setConnectTimeout(Duration.ofSeconds(2))
                .setConnectionTtl(connectionTtl).setConnectionSemaphoreFactory(holdingTheRefused))) {
            ConnectionRecorder first = new ConnectionRecorder();
            client.executeRequest(Dsl.get(httpsUrl("/ok")).setNameResolver(resolver), first).get(10, SECONDS);
            onlyConnection.complete(first.connection());

            // Pinned to the other IP, so it needs a connection of its own, and the permit the first one holds.
            ConnectionRecorder second = new ConnectionRecorder();
            Future<ListenableFuture<Response>> sent = caller.submit(
                    () -> client.executeRequest(Dsl.get(httpsUrl("/ok")).setNameResolver(resolver), second));
            assertTrue(refused.await(10, SECONDS), "the second request must be refused the permit");
            Channel parent = negotiatedHttp2Channel();
            assertNotNull(parent);
            takeAway.accept(parent);

            assertEquals(200, sent.get(10, SECONDS).get(10, SECONDS).getStatusCode());
            assertNotNull(second.opened.get(), "the refused request must open the replacement itself");
            assertNotSame(first.connection(), second.connection());
        } finally {
            caller.shutdownNow();
        }
    }

    /**
     * Creates an AHC client with a specific request timeout.
     */
    private AsyncHttpClient http2ClientWithTimeout(int requestTimeoutMs) {
        return http2ClientWithConfig(b -> b.setRequestTimeout(Duration.ofMillis(requestTimeoutMs)));
    }

    /**
     * Creates an AHC client configured for redirect tests.
     */
    private AsyncHttpClient http2ClientWithRedirects(int maxRedirects) {
        return http2ClientWithConfig(b -> b.setMaxRedirects(maxRedirects).setFollowRedirect(true));
    }

    // -------------------------------------------------------------------------
    // Existing test cases
    // -------------------------------------------------------------------------

    @Test
    public void simpleGetOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareGet(httpsUrl("/hello"))
                    .execute()
                    .get(30, SECONDS);

            assertNotNull(response);
            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    public void postStringBodyOverHttp2() throws Exception {
        String body = "Hello HTTP/2 world!";
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.preparePost(httpsUrl("/echo"))
                    .setBody(body)
                    .setHeader(CONTENT_TYPE, "text/plain")
                    .execute()
                    .get(30, SECONDS);

            assertNotNull(response);
            assertEquals(200, response.getStatusCode());
            assertEquals(body, response.getResponseBody());
        }
    }

    @Test
    public void postByteArrayBodyOverHttp2() throws Exception {
        byte[] body = "Binary data over HTTP/2".getBytes(UTF_8);
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.preparePost(httpsUrl("/echo"))
                    .setBody(body)
                    .setHeader(CONTENT_TYPE, "application/octet-stream")
                    .execute()
                    .get(30, SECONDS);

            assertNotNull(response);
            assertEquals(200, response.getStatusCode());
            assertArrayEquals(body, response.getResponseBodyAsBytes());
        }
    }

    @Test
    public void largeBodyOverHttp2() throws Exception {
        // 64KB body to test DATA frame handling
        byte[] body = new byte[64 * 1024];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i % 256);
        }
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.preparePost(httpsUrl("/echo"))
                    .setBody(body)
                    .setHeader(CONTENT_TYPE, "application/octet-stream")
                    .execute()
                    .get(30, SECONDS);

            assertNotNull(response);
            assertEquals(200, response.getStatusCode());
            assertArrayEquals(body, response.getResponseBodyAsBytes());
        }
    }

    @Test
    public void multipleSequentialRequestsOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            for (int i = 0; i < 5; i++) {
                String body = "Request " + i;
                Response response = client.preparePost(httpsUrl("/echo"))
                        .setBody(body)
                        .setHeader(CONTENT_TYPE, "text/plain")
                        .execute()
                        .get(30, SECONDS);

                assertNotNull(response);
                assertEquals(200, response.getStatusCode());
                assertEquals(body, response.getResponseBody());
            }
        }
    }

    @Test
    public void multipleConcurrentRequestsOverHttp2() throws Exception {
        int numRequests = 10;
        CountDownLatch latch = new CountDownLatch(numRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Throwable> error = new AtomicReference<>();

        try (AsyncHttpClient client = http2Client()) {
            List<CompletableFuture<Response>> futures = new ArrayList<>();
            for (int i = 0; i < numRequests; i++) {
                String body = "Concurrent request " + i;
                CompletableFuture<Response> future = client.preparePost(httpsUrl("/echo"))
                        .setBody(body)
                        .setHeader(CONTENT_TYPE, "text/plain")
                        .execute()
                        .toCompletableFuture()
                        .whenComplete((r, t) -> {
                            if (t != null) {
                                error.compareAndSet(null, t);
                            } else {
                                successCount.incrementAndGet();
                            }
                            latch.countDown();
                        });
                futures.add(future);
            }

            assertTrue(latch.await(30, SECONDS), "Timed out waiting for concurrent requests");
            assertNull(error.get(), "Unexpected error: " + error.get());
            assertEquals(numRequests, successCount.get());
        }
    }

    @Test
    public void http2HeadersContainPseudoHeaders() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareGet(httpsUrl("/headers-check"))
                    .addHeader("X-Custom-Header", "test-value")
                    .execute()
                    .get(30, SECONDS);

            assertNotNull(response);
            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    public void http2ResponseReportsCorrectProtocol() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareGet(httpsUrl("/hello"))
                    .execute()
                    .get(30, SECONDS);

            assertNotNull(response);
            assertEquals(200, response.getStatusCode());
            assertEquals(HttpProtocol.HTTP_2, response.getProtocol(),
                    "Response should report HTTP/2 protocol");
        }
    }

    @Test
    public void http2DisabledFallsBackToHttp11() throws Exception {
        try (AsyncHttpClient client = http1Client()) {
            assertNotNull(client);
        }
    }

    @Test
    public void http2IsEnabledByDefault() {
        AsyncHttpClientConfig defaultConfig = config().build();
        assertTrue(defaultConfig.isHttp2Enabled(),
                "HTTP/2 should be enabled by default");
    }

    @Test
    public void http2CanBeDisabledViaConfig() {
        AsyncHttpClientConfig configWithHttp2Disabled = config()
                .setHttp2Enabled(false)
                .build();
        assertFalse(configWithHttp2Disabled.isHttp2Enabled(),
                "HTTP/2 should be disabled when setHttp2Enabled(false) is called");
    }

    // -------------------------------------------------------------------------
    // Basic request/response tests (mirrored from BasicHttpTest)
    // -------------------------------------------------------------------------

    @Test
    public void getRootUrlOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareGet(httpsUrl("/ok"))
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    public void getResponseBodyOverHttp2() throws Exception {
        String body = "Hello World";
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.preparePost(httpsUrl("/echo"))
                    .setBody(body)
                    .setHeader(CONTENT_TYPE, "text/plain")
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals(body, response.getResponseBody());
        }
    }

    @Test
    public void getEmptyBodyOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareGet(httpsUrl("/ok"))
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            assertTrue(response.getResponseBody().isEmpty());
        }
    }

    @Test
    public void getEmptyBodyNotifiesHandlerOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            final AtomicBoolean handlerWasNotified = new AtomicBoolean();

            client.prepareGet(httpsUrl("/ok")).execute(new AsyncCompletionHandlerAdapter() {
                @Override
                public Response onCompleted(Response response) {
                    assertEquals(200, response.getStatusCode());
                    handlerWasNotified.set(true);
                    return response;
                }
            }).get(30, SECONDS);

            assertTrue(handlerWasNotified.get());
        }
    }

    @Test
    public void headHasEmptyBodyOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareHead(httpsUrl("/head"))
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            assertTrue(response.getResponseBody().isEmpty());
        }
    }

    @Test
    public void defaultRequestBodyEncodingIsUtf8OverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.preparePost(httpsUrl("/echo"))
                    .setBody("\u017D\u017D\u017D\u017D\u017D\u017D")
                    .execute()
                    .get(30, SECONDS);

            assertArrayEquals(response.getResponseBodyAsBytes(),
                    "\u017D\u017D\u017D\u017D\u017D\u017D".getBytes(UTF_8));
        }
    }

    // -------------------------------------------------------------------------
    // Path and query string tests
    // -------------------------------------------------------------------------

    @Test
    public void getUrlWithPathWithoutQueryOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareGet(httpsUrl("/foo/bar"))
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("/foo/bar", response.getHeader("X-PathInfo"));
        }
    }

    @Test
    public void getUrlWithPathWithQueryOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareGet(httpsUrl("/foo/bar?q=+%20x"))
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("/foo/bar", response.getHeader("X-PathInfo"));
            assertNotNull(response.getHeader("X-QueryString"));
        }
    }

    @Test
    public void getUrlWithPathWithQueryParamsOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareGet(httpsUrl("/foo/bar"))
                    .addQueryParam("q", "a b")
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            assertNotNull(response.getHeader("X-QueryString"));
        }
    }

    @Test
    public void getProperPathAndQueryStringOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareGet(httpsUrl("/foo/bar?foo=bar"))
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            assertNotNull(response.getHeader("X-PathInfo"));
            assertNotNull(response.getHeader("X-QueryString"));
        }
    }

    // -------------------------------------------------------------------------
    // Headers and cookies tests
    // -------------------------------------------------------------------------

    @Test
    public void getWithHeadersOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareGet(httpsUrl("/echo"))
                    .addHeader("Test1", "Test1")
                    .addHeader("Test2", "Test2")
                    .addHeader("Test3", "Test3")
                    .addHeader("Test4", "Test4")
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            for (int i = 1; i < 5; i++) {
                assertEquals("Test" + i, response.getHeader("X-test" + i));
            }
        }
    }

    /**
     * Regression guard for the HTTP/2 header copy: a user-supplied mixed-case header name must be
     * lowercased before it reaches the validating {@link DefaultHttp2Headers}, which otherwise throws
     * {@code Http2Exception: invalid header name}. Asserts (a) the request succeeds, (b) the name is
     * lowercase on the wire, and (c) connection-specific names are not forwarded.
     */
    @Test
    public void mixedCaseHeaderIsLowercasedAndConnectionHeadersExcludedOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareGet(httpsUrl("/echo"))
                    .addHeader("X-Mixed-Case", "v1")
                    .addHeader("Another-Custom-Header", "v2")
                    .addHeader("connection", "keep-alive")
                    .addHeader("Keep-Alive", "timeout=5")
                    .addHeader("Upgrade", "h2c")
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());

            // x-received-names reports the exact, case-preserving names the server decoded off the wire.
            String received = response.getHeader("x-received-names");
            assertNotNull(received, "server should report received header names");
            List<String> names = Arrays.asList(received.split(","));

            // (b) mixed-case user headers arrive lowercase on the wire (case-sensitive membership check)
            assertTrue(names.contains("x-mixed-case"),
                    "mixed-case header should be lowercase on the wire, got: " + received);
            assertTrue(names.contains("another-custom-header"),
                    "mixed-case header should be lowercase on the wire, got: " + received);
            assertFalse(names.contains("X-Mixed-Case"),
                    "uppercase header name must not appear on the wire, got: " + received);

            // (c) connection-specific names (any casing) are dropped, not forwarded as regular headers
            for (String forbidden : new String[]{"connection", "keep-alive", "upgrade", "host"}) {
                assertFalse(names.contains(forbidden),
                        "connection-specific header '" + forbidden + "' must be excluded, got: " + received);
            }

            // and the values still round-trip for the forwarded headers
            assertEquals("v1", response.getHeader("X-x-mixed-case"));
            assertEquals("v2", response.getHeader("X-another-custom-header"));
        }
    }

    @Test
    public void generatedAcceptEncodingUsesHpackStaticValueOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2ClientWithConfig(builder -> builder.setCompressionEnforced(true))) {
            Response response = client.prepareGet(httpsUrl("/echo"))
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            List<String> expected = new ArrayList<>();
            expected.add("gzip, deflate");
            if (Brotli.isAvailable()) {
                expected.add("br");
            }
            if (Zstd.isAvailable()) {
                expected.add("zstd");
            }
            assertEquals(expected, response.getHeaders("X-accept-encoding"));
        }
    }

    @Test
    public void userAcceptEncodingSpellingIsPreservedOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2ClientWithConfig(builder -> builder.setCompressionEnforced(true))) {
            Response response = client.prepareGet(httpsUrl("/echo"))
                    .setHeader(ACCEPT_ENCODING, "gzip,deflate")
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("gzip,deflate", response.getHeader("X-accept-encoding"));
        }
    }

    @Test
    public void postWithHeadersAndFormParamsOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Map<String, List<String>> m = new HashMap<>();
            for (int i = 0; i < 5; i++) {
                m.put("param_" + i, Collections.singletonList("value_" + i));
            }

            Response response = client.preparePost(httpsUrl("/echo"))
                    .setHeader(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED)
                    .setFormParams(m)
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            for (int i = 0; i < 5; i++) {
                assertEquals("value_" + i,
                        URLDecoder.decode(response.getHeader("X-param_" + i), UTF_8));
            }
        }
    }

    @Test
    public void postChineseCharOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            String chineseChar = "\u662F";

            Map<String, List<String>> m = new HashMap<>();
            m.put("param", Collections.singletonList(chineseChar));

            Response response = client.preparePost(httpsUrl("/echo"))
                    .setHeader(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED)
                    .setFormParams(m)
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            String value = URLDecoder.decode(response.getHeader("X-param"), UTF_8);
            assertEquals(chineseChar, value);
        }
    }

    @Test
    public void getWithCookiesOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareGet(httpsUrl("/cookies"))
                    .addHeader("cookie", "foo=value")
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            String setCookie = response.getHeader("set-cookie");
            assertNotNull(setCookie);
            assertTrue(setCookie.contains("foo=value"));
        }
    }

    @Test
    public void postFormParametersAsBodyStringOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_").append(i).append("=value_").append(i).append('&');
            }
            sb.setLength(sb.length() - 1);

            Response response = client.preparePost(httpsUrl("/echo"))
                    .setHeader(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED)
                    .setBody(sb.toString())
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            for (int i = 0; i < 5; i++) {
                assertEquals("value_" + i,
                        URLDecoder.decode(response.getHeader("X-param_" + i), UTF_8));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Timeout and cancellation tests
    // -------------------------------------------------------------------------

    @Test
    public void cancelledFutureThrowsCancellationExceptionOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Future<Response> future = client.prepareGet(httpsUrl("/delay/5000"))
                    .execute(new AsyncCompletionHandlerAdapter() {
                        @Override
                        public void onThrowable(Throwable t) {
                        }
                    });
            future.cancel(true);
            assertThrows(CancellationException.class, () -> future.get(30, SECONDS));
        }
    }

    @Test
    public void futureTimeOutThrowsTimeoutExceptionOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Future<Response> future = client.prepareGet(httpsUrl("/delay/5000"))
                    .execute(new AsyncCompletionHandlerAdapter() {
                        @Override
                        public void onThrowable(Throwable t) {
                        }
                    });

            assertThrows(TimeoutException.class, () -> future.get(2, SECONDS));
        }
    }

    @Test
    public void configTimeoutNotifiesOnThrowableAndFutureOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2ClientWithTimeout(1000)) {
            final AtomicBoolean onCompletedWasNotified = new AtomicBoolean();
            final AtomicBoolean onThrowableWasNotifiedWithTimeoutException = new AtomicBoolean();
            final CountDownLatch latch = new CountDownLatch(1);

            Future<Response> whenResponse = client.prepareGet(httpsUrl("/delay/5000"))
                    .execute(new AsyncCompletionHandlerAdapter() {
                        @Override
                        public Response onCompleted(Response response) {
                            onCompletedWasNotified.set(true);
                            latch.countDown();
                            return response;
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                            onThrowableWasNotifiedWithTimeoutException.set(t instanceof TimeoutException);
                            latch.countDown();
                        }
                    });

            if (!latch.await(30, SECONDS)) {
                fail("Timed out");
            }

            assertFalse(onCompletedWasNotified.get());
            assertTrue(onThrowableWasNotifiedWithTimeoutException.get());

            assertThrows(ExecutionException.class, () -> whenResponse.get(30, SECONDS));
        }
    }

    @Test
    public void configRequestTimeoutHappensInDueTimeOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2ClientWithTimeout(1000)) {
            long start = unpreciseMillisTime();
            try {
                client.prepareGet(httpsUrl("/delay/2000")).execute().get();
                fail("Should have thrown");
            } catch (ExecutionException ex) {
                final long elapsedTime = unpreciseMillisTime() - start;
                assertTrue(elapsedTime >= 1_000 && elapsedTime <= 1_500,
                        "Elapsed time was " + elapsedTime + "ms");
            }
        }
    }

    @Test
    public void cancellingFutureNotifiesOnThrowableWithCancellationExceptionOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            CountDownLatch latch = new CountDownLatch(1);

            Future<Response> future = client.preparePost(httpsUrl("/delay/2000"))
                    .setBody("Body")
                    .execute(new AsyncCompletionHandlerAdapter() {
                        @Override
                        public void onThrowable(Throwable t) {
                            if (t instanceof CancellationException) {
                                latch.countDown();
                            }
                        }
                    });

            future.cancel(true);
            if (!latch.await(30, SECONDS)) {
                fail("Timed out");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Handler exception notification tests
    // -------------------------------------------------------------------------

    @Test
    public void exceptionInOnCompletedGetNotifiedToOnThrowableOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> message = new AtomicReference<>();

            client.prepareGet(httpsUrl("/ok")).execute(new AsyncCompletionHandlerAdapter() {
                @Override
                public Response onCompleted(Response response) {
                    throw unknownStackTrace(new IllegalStateException("FOO"),
                            BasicHttp2Test.class, "exceptionInOnCompletedGetNotifiedToOnThrowableOverHttp2");
                }

                @Override
                public void onThrowable(Throwable t) {
                    message.set(t.getMessage());
                    latch.countDown();
                }
            });

            if (!latch.await(30, SECONDS)) {
                fail("Timed out");
            }

            assertEquals("FOO", message.get());
        }
    }

    @Test
    public void exceptionInOnCompletedGetNotifiedToFutureOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Future<Response> whenResponse = client.prepareGet(httpsUrl("/ok"))
                    .execute(new AsyncCompletionHandlerAdapter() {
                        @Override
                        public Response onCompleted(Response response) {
                            throw unknownStackTrace(new IllegalStateException("FOO"),
                                    BasicHttp2Test.class, "exceptionInOnCompletedGetNotifiedToFutureOverHttp2");
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                        }
                    });

            try {
                whenResponse.get(30, SECONDS);
                fail("Should have thrown");
            } catch (ExecutionException e) {
                assertInstanceOf(IllegalStateException.class, e.getCause());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Redirects and methods tests
    // -------------------------------------------------------------------------

    @Test
    public void reachingMaxRedirectThrowsMaxRedirectExceptionOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2ClientWithRedirects(1)) {
            try {
                client.prepareGet(httpsUrl("/redirect/3"))
                        .execute(new AsyncCompletionHandlerAdapter() {
                            @Override
                            public Response onCompleted(Response response) {
                                fail("Should not be here");
                                return response;
                            }

                            @Override
                            public void onThrowable(Throwable t) {
                            }
                        }).get(30, SECONDS);
                fail("Should have thrown");
            } catch (ExecutionException e) {
                assertInstanceOf(MaxRedirectException.class, e.getCause());
            }
        }
    }

    @Test
    public void optionsIsSupportedOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.prepareOptions(httpsUrl("/options"))
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("GET,HEAD,POST,OPTIONS,TRACE", response.getHeader("allow"));
        }
    }

    // -------------------------------------------------------------------------
    // Connection events tests
    // -------------------------------------------------------------------------

    @Test
    public void newConnectionEventsAreFiredOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            EventCollectingHandler handler = new EventCollectingHandler();
            client.prepareGet(httpsUrl("/ok")).execute(handler).get(30, SECONDS);
            handler.waitForCompletion(30, SECONDS);

            Object[] expectedEvents = {
                    CONNECTION_POOL_EVENT,
                    HOSTNAME_RESOLUTION_EVENT,
                    HOSTNAME_RESOLUTION_SUCCESS_EVENT,
                    CONNECTION_OPEN_EVENT,
                    CONNECTION_SUCCESS_EVENT,
                    TLS_HANDSHAKE_EVENT,
                    TLS_HANDSHAKE_SUCCESS_EVENT,
                    REQUEST_SEND_EVENT,
                    STATUS_RECEIVED_EVENT,
                    HEADERS_RECEIVED_EVENT,
                    CONNECTION_OFFER_EVENT,
                    COMPLETED_EVENT};

            assertArrayEquals(expectedEvents, handler.firedEvents.toArray(),
                    "Got " + Arrays.toString(handler.firedEvents.toArray()));
        }
    }

    // -------------------------------------------------------------------------
    // HTTP/2-specific tests
    // -------------------------------------------------------------------------

    @Test
    public void http2ErrorStatusCodesAreReported() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            Response response404 = client.prepareGet(httpsUrl("/status/404"))
                    .execute()
                    .get(30, SECONDS);
            assertEquals(404, response404.getStatusCode());

            Response response500 = client.prepareGet(httpsUrl("/status/500"))
                    .execute()
                    .get(30, SECONDS);
            assertEquals(500, response500.getStatusCode());
        }
    }

    @Test
    public void http2StreamResetIsHandledGracefully() throws Exception {
        try (AsyncHttpClient client = http2ClientWithTimeout(5000)) {
            try {
                client.prepareGet(httpsUrl("/reset"))
                        .execute()
                        .get(10, SECONDS);
                fail("Should have thrown");
            } catch (ExecutionException e) {
                assertNotNull(e.getCause());
            }
        }
    }

    @Test
    public void postByteBodyOverHttp2() throws Exception {
        byte[] bodyBytes = "Hello from byte array body".getBytes(UTF_8);
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.preparePost(httpsUrl("/echo"))
                    .setHeader(CONTENT_TYPE, "application/octet-stream")
                    .setBody(bodyBytes)
                    .execute()
                    .get(30, SECONDS);

            assertEquals(200, response.getStatusCode());
            assertArrayEquals(bodyBytes, response.getResponseBodyAsBytes());
        }
    }

    // -------------------------------------------------------------------------
    // HTTP/2 multiplexing and connection management tests
    // -------------------------------------------------------------------------

    @Test
    public void http2MultiplexesConcurrentRequestsOnSingleConnection() throws Exception {
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setMaxConnectionsPerHost(1))) {
            int concurrentRequests = 10;
            CountDownLatch latch = new CountDownLatch(concurrentRequests);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            // Fire off concurrent requests — with maxConnectionsPerHost=1 and HTTP/1.1,
            // these would block waiting for the single connection. With HTTP/2 multiplexing,
            // they should all complete on the same connection concurrently.
            for (int i = 0; i < concurrentRequests; i++) {
                final int idx = i;
                client.prepareGet(httpsUrl("/delay/100"))
                        .execute(new AsyncCompletionHandlerBase() {
                            @Override
                            public Response onCompleted(Response response) throws Exception {
                                if (response.getStatusCode() == 200) {
                                    successCount.incrementAndGet();
                                }
                                latch.countDown();
                                return response;
                            }

                            @Override
                            public void onThrowable(Throwable t) {
                                firstError.compareAndSet(null, t);
                                latch.countDown();
                            }
                        });
            }

            assertTrue(latch.await(30, SECONDS), "All requests should complete within 30s");
            assertNull(firstError.get(), "No errors expected, got: " + firstError.get());
            assertEquals(concurrentRequests, successCount.get(),
                    "All concurrent requests should succeed via HTTP/2 multiplexing");
        }
    }

    @Test
    public void http2ConnectionIsReusedAcrossSequentialRequests() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            // First request — establishes the HTTP/2 connection
            Response response1 = client.prepareGet(httpsUrl("/ok")).execute().get(30, SECONDS);
            assertEquals(200, response1.getStatusCode());

            // Second request — should reuse the same HTTP/2 connection from the registry
            EventCollectingHandler handler = new EventCollectingHandler();
            Response response2 = client.prepareGet(httpsUrl("/ok")).execute(handler).get(30, SECONDS);
            assertEquals(200, response2.getStatusCode());
            handler.waitForCompletion(30, SECONDS);

            // The second request should hit the connection pool (HTTP/2 registry) and NOT
            // open a new connection — no DNS resolution, no TLS handshake
            var events = handler.firedEvents;
            assertTrue(events.contains(CONNECTION_POOL_EVENT), "Should attempt pool lookup");
            assertFalse(events.contains(HOSTNAME_RESOLUTION_EVENT),
                    "Should NOT resolve hostname for reused H2 connection");
            assertFalse(events.contains(TLS_HANDSHAKE_EVENT),
                    "Should NOT do TLS handshake for reused H2 connection");
        }
    }

    @Test
    public void http2SequentialRequestsWithMaxConnectionsPerHostOne() throws Exception {
        // Verify that with maxConnectionsPerHost=1, sequential HTTP/2 requests don't deadlock
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setMaxConnectionsPerHost(1))) {
            for (int i = 0; i < 5; i++) {
                Response response = client.prepareGet(httpsUrl("/ok")).execute().get(30, SECONDS);
                assertEquals(200, response.getStatusCode());
            }
        }
    }

    /**
     * A request that fails to build keeps an HTTP/2 connection open: unlike an HTTP/1.1 one, it never left the
     * registry, and other requests may be using it.
     */
    @Test
    public void http2ConnectionStaysOpenWhenARequestFailsToBuild() throws Exception {
        File missing = new File(createTempFile(1).getParentFile(), "missing-" + System.nanoTime());
        try (AsyncHttpClient client = http2ClientWithConfig(b -> b.setMaxConnections(1))) {
            ConnectionRecorder first = new ConnectionRecorder();
            assertEquals(200, client.prepareGet(httpsUrl("/ok")).execute(first).get(30, SECONDS).getStatusCode());

            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> client.preparePost(httpsUrl("/ok")).setBody(missing).execute().get(30, SECONDS));
            assertInstanceOf(IllegalArgumentException.class, e.getCause());

            ConnectionRecorder next = new ConnectionRecorder();
            assertEquals(200, client.prepareGet(httpsUrl("/ok")).execute(next).get(30, SECONDS).getStatusCode());
            assertSame(first.connection(), next.connection(), "the next request should reuse the HTTP/2 connection");
            assertTrue(first.connection().isActive(), "the HTTP/2 connection should still be open");
        }
    }

    /**
     * Http2Handler gates its own IOExceptionFilter replay on the exception type, so both arms are pinned.
     */
    @Test
    public void schemeDowngradeRefusalAppliesOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2ClientWithConfig(builder -> builder
                .setFollowRedirect(true)
                .setRefuseSchemeDowngradeOnRedirect(true))) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> client.prepareGet(httpsUrl("/downgrade")).execute().get(10, TimeUnit.SECONDS));
            assertInstanceOf(RedirectRefusedException.class, failure.getCause());
        }
    }

    @Test
    public void crossOriginBodyRefusalAppliesOverHttp2() throws Exception {
        try (AsyncHttpClient client = http2ClientWithConfig(builder -> builder
                .setFollowRedirect(true)
                .setRefuseCrossOriginBodyOnRedirect(true))) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> client.preparePut(httpsUrl("/cross-origin")).setBody("payload")
                            .execute().get(10, TimeUnit.SECONDS));
            assertInstanceOf(RedirectRefusedException.class, failure.getCause());
        }
    }
}
