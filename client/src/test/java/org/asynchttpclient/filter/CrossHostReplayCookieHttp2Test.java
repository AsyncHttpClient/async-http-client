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
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
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
import io.netty.util.ReferenceCountUtil;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.cookie.ThreadSafeCookieStore;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CrossHostReplayCookieTest} over HTTP/2: the replay leaves a stream channel and opens a stream to the
 * other origin.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
public class CrossHostReplayCookieHttp2Test {

    private static final String RECEIVED_COOKIE = "received-cookie";

    private NioEventLoopGroup serverGroup;
    private Channel serverChannel;
    private SslContext serverSslCtx;
    private int serverPort;

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
                                        streamCh.pipeline().addLast(new CookieEchoHandler());
                                    }
                                }));
                    }
                })
                .bind(0).sync().channel();
        serverPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @AfterEach
    public void stopServer() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        if (serverGroup != null) {
            serverGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).sync();
        }
        ReferenceCountUtil.release(serverSslCtx);
    }

    @Test
    public void aCloneReplayedToAnotherHostCarriesOnlyTheStoresCookiesForIt() throws Exception {
        String a = "https://localhost:" + serverPort;
        String b = "https://127.0.0.1:" + serverPort;
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create(a + "/"), ClientCookieDecoder.STRICT.decode("A_SESSION=alice; Path=/"));
        store.add(Uri.create(b + "/"), ClientCookieDecoder.STRICT.decode("B_SESSION=bob; Path=/"));
        ResponseFilter failover = new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (ctx.getResponseStatus() != null && ctx.getResponseStatus().getStatusCode() == 503) {
                    return new FilterContext.FilterContextBuilder<>(ctx)
                            .request(ctx.getRequest().toBuilder().setUrl(b + "/sink").build())
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setCookieStore(store)
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .addResponseFilter(failover))) {
            Response response = client.prepareGet(a + "/fail").execute().get(10, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("127.0.0.1", response.getUri().getHost());
            assertEquals("B_SESSION=bob", response.getHeader(RECEIVED_COOKIE));
        }
    }

    /** Answers 503 on /fail and otherwise echoes the Cookie header it got. */
    private static final class CookieEchoHandler extends SimpleChannelInboundHandler<Http2HeadersFrame> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Http2HeadersFrame frame) {
            Http2Headers request = frame.headers();
            if ("/fail".contentEquals(request.path())) {
                // With a body: a HEADERS frame that ends the stream completes the exchange even when it is replayed.
                ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("503"), false));
                ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.copiedBuffer("unavailable", US_ASCII), true));
                return;
            }
            Http2Headers response = new DefaultHttp2Headers().status("200");
            List<CharSequence> cookies = request.getAll("cookie");
            if (!cookies.isEmpty()) {
                response.set(RECEIVED_COOKIE, String.join("; ", cookies));
            }
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(response, true));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
