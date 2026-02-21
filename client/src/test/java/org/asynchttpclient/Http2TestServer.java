/*
 *    Copyright (c) 2024 AsyncHttpClient Project. All rights reserved.
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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A simple Netty-based HTTP/2 server for testing.
 * Supports TLS with ALPN negotiation.
 */
public class Http2TestServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(Http2TestServer.class);

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private Channel serverChannel;
    private int port;

    public Http2TestServer() {
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
    }

    public void start() throws Exception {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        SslContext sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2))
                .build();

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(sslCtx.newHandler(ch.alloc()));
                        ch.pipeline().addLast(new Http2OrHttpHandler());
                    }
                });

        ChannelFuture f = b.bind(0).sync();
        serverChannel = f.channel();
        port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        LOGGER.info("HTTP/2 test server started on port {}", port);
    }

    public int getPort() {
        return port;
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    /**
     * ALPN negotiation handler - selects HTTP/2 or rejects
     */
    private static class Http2OrHttpHandler extends ApplicationProtocolNegotiationHandler {

        Http2OrHttpHandler() {
            super(ApplicationProtocolNames.HTTP_2);
        }

        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                ctx.pipeline().addLast(
                        Http2FrameCodecBuilder.forServer().build(),
                        new Http2MultiplexHandler(new Http2ServerStreamHandler()));
            } else {
                throw new IllegalStateException("Unsupported protocol: " + protocol);
            }
        }
    }

    /**
     * Handler for each HTTP/2 stream. Processes request frames and sends response frames.
     */
    @Sharable
    static class Http2ServerStreamHandler extends ChannelInboundHandlerAdapter {

        private Http2Headers requestHeaders;
        private final StringBuilder requestBody = new StringBuilder();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2HeadersFrame) {
                    Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                    requestHeaders = headersFrame.headers();
                    LOGGER.debug("Server received headers: method={}, path={}", requestHeaders.method(), requestHeaders.path());
                    
                    if (headersFrame.isEndStream()) {
                        sendResponse(ctx);
                    }
                } else if (msg instanceof Http2DataFrame) {
                    Http2DataFrame dataFrame = (Http2DataFrame) msg;
                    ByteBuf content = dataFrame.content();
                    if (content.isReadable()) {
                        requestBody.append(content.toString(StandardCharsets.UTF_8));
                    }
                    
                    if (dataFrame.isEndStream()) {
                        sendResponse(ctx);
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        private void sendResponse(ChannelHandlerContext ctx) {
            String path = requestHeaders.path() != null ? requestHeaders.path().toString() : "/";
            String method = requestHeaders.method() != null ? requestHeaders.method().toString() : "GET";

            switch (path) {
                case "/echo":
                    sendEchoResponse(ctx, method);
                    break;
                case "/headers":
                    sendHeadersResponse(ctx);
                    break;
                case "/redirect":
                    sendRedirectResponse(ctx);
                    break;
                case "/large":
                    sendLargeResponse(ctx);
                    break;
                case "/empty":
                    sendEmptyResponse(ctx);
                    break;
                case "/redirect-target":
                    sendTextResponse(ctx, 200, "Redirect Target Reached");
                    break;
                default:
                    sendTextResponse(ctx, 200, "Hello HTTP/2 World");
                    break;
            }
        }

        private void sendEchoResponse(ChannelHandlerContext ctx, String method) {
            String body = requestBody.length() > 0 ? requestBody.toString() : "No Body";
            Http2Headers headers = new DefaultHttp2Headers()
                    .status("200")
                    .add("x-method", method)
                    .add("content-type", "text/plain");

            ctx.write(new DefaultHttp2HeadersFrame(headers));
            ByteBuf content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
            ctx.writeAndFlush(new DefaultHttp2DataFrame(content, true));
        }

        private void sendHeadersResponse(ChannelHandlerContext ctx) {
            Http2Headers headers = new DefaultHttp2Headers()
                    .status("200")
                    .add("x-custom-header", "custom-value")
                    .add("x-multi-value", "value1")
                    .add("x-multi-value", "value2")
                    .add("content-type", "text/plain");

            // Echo back all request headers as x-echo-* response headers
            if (requestHeaders != null) {
                for (java.util.Map.Entry<CharSequence, CharSequence> entry : requestHeaders) {
                    String name = entry.getKey().toString();
                    if (!name.startsWith(":")) {
                        headers.add("x-echo-" + name, entry.getValue());
                    }
                }
            }

            ctx.write(new DefaultHttp2HeadersFrame(headers));
            ByteBuf content = Unpooled.copiedBuffer("Headers Response", StandardCharsets.UTF_8);
            ctx.writeAndFlush(new DefaultHttp2DataFrame(content, true));
        }

        private void sendRedirectResponse(ChannelHandlerContext ctx) {
            // Determine scheme from request
            CharSequence scheme = requestHeaders != null ? requestHeaders.scheme() : "https";
            CharSequence authority = requestHeaders != null ? requestHeaders.authority() : "localhost";

            Http2Headers headers = new DefaultHttp2Headers()
                    .status("302")
                    .add("location", scheme + "://" + authority + "/redirect-target");

            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));
        }

        private void sendLargeResponse(ChannelHandlerContext ctx) {
            Http2Headers headers = new DefaultHttp2Headers()
                    .status("200")
                    .add("content-type", "text/plain");

            ctx.write(new DefaultHttp2HeadersFrame(headers));

            // Send a large body in chunks
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                sb.append("Line ").append(i).append(": This is a test line for HTTP/2 large response body.\n");
            }
            ByteBuf content = Unpooled.copiedBuffer(sb.toString(), StandardCharsets.UTF_8);
            ctx.writeAndFlush(new DefaultHttp2DataFrame(content, true));
        }

        private void sendEmptyResponse(ChannelHandlerContext ctx) {
            Http2Headers headers = new DefaultHttp2Headers()
                    .status("204");
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));
        }

        private void sendTextResponse(ChannelHandlerContext ctx, int statusCode, String body) {
            Http2Headers headers = new DefaultHttp2Headers()
                    .status(String.valueOf(statusCode))
                    .add("content-type", "text/plain");

            ctx.write(new DefaultHttp2HeadersFrame(headers));
            ByteBuf content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
            ctx.writeAndFlush(new DefaultHttp2DataFrame(content, true));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOGGER.error("HTTP/2 server stream error", cause);
            ctx.close();
        }
    }
}
