/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.filter;

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import io.netty.util.ReferenceCountUtil;
import org.asynchttpclient.AsyncHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An {@link IOExceptionFilter} replays a request whose replay cannot be built, here because its body file is gone.
 * The replay starts on a stream channel it has already let go of, so it has to fail the request itself rather than
 * leave it to its request timeout.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
public class FailedReplayHttp2Test {

    private NioEventLoopGroup serverGroup;
    private Channel serverChannel;
    private SslContext serverSslCtx;
    private int serverPort;
    // The server deletes it once it has received the request body, before it answers with a 307.
    private volatile File body;

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
                        ApplicationProtocolNames.HTTP_2))
                .build();
        serverGroup = new NioEventLoopGroup(1);
        serverChannel = new ServerBootstrap()
                .group(serverGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline()
                                .addLast(serverSslCtx.newHandler(ch.alloc()))
                                .addLast(Http2FrameCodecBuilder.forServer().build())
                                .addLast(new Http2MultiplexHandler(new ChannelInitializer<Http2StreamChannel>() {
                                    @Override
                                    protected void initChannel(Http2StreamChannel streamCh) {
                                        streamCh.pipeline().addLast(new DeletingRedirectHandler());
                                    }
                                }));
                    }
                })
                .bind(0).sync().channel();
        serverPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @AfterEach
    public void stopServer() throws Exception {
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        if (serverGroup != null) {
            serverGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).sync();
        }
        ReferenceCountUtil.release(serverSslCtx);
        if (body != null) {
            Files.deleteIfExists(body.toPath());
        }
    }

    @Test
    public void aReplayThatCannotBeBuiltFailsTheRequest() throws Exception {
        body = File.createTempFile("failed-replay", ".txt");
        Files.write(body.toPath(), "body".getBytes(US_ASCII));
        AtomicInteger replays = new AtomicInteger();
        IOExceptionFilter replayOnce = new IOExceptionFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                return replays.getAndIncrement() == 0
                        ? new FilterContext.FilterContextBuilder<>(ctx).replayRequest(true).build()
                        : ctx;
            }
        };

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setFollowRedirect(true)
                .addIOExceptionFilter(replayOnce))) {
            String base = "https://localhost:" + serverPort;
            // The redirect refuses to resend the missing file with an IOException, which the filter replays.
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> client.preparePost(base + "/redirect").setBody(body).execute().get(10, TimeUnit.SECONDS));

            assertInstanceOf(IllegalArgumentException.class, e.getCause());
            assertEquals(1, replays.get());
            // The client still serves requests.
            assertEquals(200, client.prepareGet(base + "/ok").execute().get(10, TimeUnit.SECONDS).getStatusCode());
        }
    }

    /** Deletes the body file once a request on /redirect has ended, then answers with a 307. Answers 200 otherwise. */
    private final class DeletingRedirectHandler extends SimpleChannelInboundHandler<Http2StreamFrame> {

        private boolean redirect;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Http2StreamFrame frame) throws Exception {
            boolean endStream;
            if (frame instanceof Http2HeadersFrame) {
                Http2HeadersFrame headers = (Http2HeadersFrame) frame;
                redirect = "/redirect".contentEquals(headers.headers().path());
                endStream = headers.isEndStream();
            } else if (frame instanceof Http2DataFrame) {
                endStream = ((Http2DataFrame) frame).isEndStream();
            } else {
                return;
            }
            if (!endStream) {
                return;
            }
            if (redirect) {
                Files.delete(body.toPath());
                ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("307").set("location", "/target"),
                        false));
                ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.copiedBuffer("moved", US_ASCII), true));
            } else {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), true));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
