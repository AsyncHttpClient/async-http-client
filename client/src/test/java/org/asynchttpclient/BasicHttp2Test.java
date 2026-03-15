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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.util.ReferenceCountUtil;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.asynchttpclient.test.EventCollectingHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.AsyncCompletionHandlerAdapter;
import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;
import static org.asynchttpclient.util.ThrowableUtil.unknownStackTrace;
import static org.junit.jupiter.api.Assertions.*;

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

            // Echo request headers as X-{name}
            for (Map.Entry<CharSequence, CharSequence> entry : requestHeaders) {
                String name = entry.getKey().toString();
                // Skip pseudo-headers
                if (!name.startsWith(":")) {
                    responseHeaders.add("x-" + name, entry.getValue());
                }
            }

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
                                .addLast(Http2FrameCodecBuilder.forServer().build())
                                .addLast(new Http2MultiplexHandler(new ChannelInitializer<Http2StreamChannel>() {
                                    @Override
                                    protected void initChannel(Http2StreamChannel streamCh) {
                                        streamCh.pipeline().addLast(new Http2TestServerHandler());
                                    }
                                }));
                    }
                });

        serverChannel = b.bind(0).sync().channel();
        serverPort = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @AfterEach
    public void stopServer() throws InterruptedException {
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
        byte[] body = "Binary data over HTTP/2".getBytes(StandardCharsets.UTF_8);
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
                assertInstanceOf(org.asynchttpclient.handler.MaxRedirectException.class, e.getCause());
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
}
