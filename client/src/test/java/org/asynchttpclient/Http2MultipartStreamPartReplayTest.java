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
package org.asynchttpclient;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
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
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import io.netty.util.ReferenceCountUtil;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.request.body.multipart.InputStreamPart;
import org.asynchttpclient.request.body.multipart.Part;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A replay of an HTTP/2 request must not send a multipart {@link InputStreamPart} from where the first attempt
 * left its stream, and must send the streams of parts that a filter supplied for the replay as they are.
 */
public class Http2MultipartStreamPartReplayTest {

    private static final String PAYLOAD = "stream part payload";

    private final AtomicInteger requests = new AtomicInteger();
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
                                        streamCh.pipeline().addLast(new EchoHandler());
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
    public void replaySendsAResettableStreamPartAgain() throws Exception {
        try (AsyncHttpClient client = replayingClient()) {
            Response response = client.preparePost("https://localhost:" + serverPort + "/")
                    .addBodyPart(new InputStreamPart("file", new ByteArrayInputStream(PAYLOAD.getBytes(UTF_8)), "file.bin"))
                    .execute()
                    .get(10, SECONDS);

            assertEquals(2, requests.get());
            assertTrue(response.getResponseBody().contains(PAYLOAD));
        }
    }

    @Test
    public void replayFailsForAStreamPartThatCannotBeReset() throws Exception {
        InputStream stream = nonResettable(PAYLOAD);
        try (AsyncHttpClient client = replayingClient()) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client
                    .preparePost("https://localhost:" + serverPort + "/")
                    .addBodyPart(new InputStreamPart("file", stream, "file.bin"))
                    .execute()
                    .get(10, SECONDS));

            IOException cause = assertInstanceOf(IOException.class, thrown.getCause());
            assertEquals("Multipart InputStream body part 'file' already consumed and cannot be reset for a retry",
                    cause.getMessage());
            assertEquals(1, requests.get());
        }
    }

    @Test
    public void replaySendsTheFreshStreamPartOfAReplacedRequest() throws Exception {
        Part fresh = new InputStreamPart("fresh", nonResettable("fresh payload"), "fresh.bin");
        try (AsyncHttpClient client = replayingClient(List.of(fresh))) {
            Response response = client.preparePost("https://localhost:" + serverPort + "/")
                    .addBodyPart(new InputStreamPart("first", nonResettable(PAYLOAD), "first.bin"))
                    .execute()
                    .get(10, SECONDS);

            assertEquals(2, requests.get());
            assertTrue(response.getResponseBody().contains("fresh payload"));
        }
    }

    @Test
    public void replayResetsAReusedStreamPartAndSendsAFreshOne() throws Exception {
        Part reused = new InputStreamPart("reused", new ByteArrayInputStream(PAYLOAD.getBytes(UTF_8)), "reused.bin");
        Part fresh = new InputStreamPart("fresh", nonResettable("fresh payload"), "fresh.bin");
        try (AsyncHttpClient client = replayingClient(List.of(reused, fresh))) {
            Response response = client.preparePost("https://localhost:" + serverPort + "/")
                    .addBodyPart(reused)
                    .execute()
                    .get(10, SECONDS);

            assertEquals(2, requests.get());
            assertTrue(response.getResponseBody().contains(PAYLOAD));
            assertTrue(response.getResponseBody().contains("fresh payload"));
        }
    }

    private static AsyncHttpClient replayingClient() {
        return replayingClient(null);
    }

    // Replays the first response's request once, with its body parts replaced by the given ones, if any.
    private static AsyncHttpClient replayingClient(@Nullable List<Part> replacementParts) {
        AtomicBoolean replay = new AtomicBoolean(true);
        ResponseFilter replayOnce = new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (replay.getAndSet(false)) {
                    Request request = replacementParts == null
                            ? ctx.getRequest()
                            : ctx.getRequest().toBuilder().setBodyParts(replacementParts).build();
                    return new FilterContext.FilterContextBuilder<T>(ctx.getAsyncHandler(), request)
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };
        return asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .addResponseFilter(replayOnce)
                .setRequestTimeout(Duration.ofSeconds(10)));
    }

    private static InputStream nonResettable(String content) {
        return new FilterInputStream(new ByteArrayInputStream(content.getBytes(UTF_8))) {
            @Override
            public boolean markSupported() {
                return false;
            }
        };
    }

    /** Answers every request with the body it received, once the request has ended. */
    private final class EchoHandler extends SimpleChannelInboundHandler<Object> {

        private final ByteBuf body = Unpooled.buffer();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            boolean endStream = false;
            if (msg instanceof Http2HeadersFrame) {
                endStream = ((Http2HeadersFrame) msg).isEndStream();
            } else if (msg instanceof Http2DataFrame) {
                body.writeBytes(((Http2DataFrame) msg).content());
                endStream = ((Http2DataFrame) msg).isEndStream();
            }
            if (endStream) {
                requests.incrementAndGet();
                ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
                ctx.writeAndFlush(new DefaultHttp2DataFrame(body.retainedDuplicate(), true));
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            body.release();
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
