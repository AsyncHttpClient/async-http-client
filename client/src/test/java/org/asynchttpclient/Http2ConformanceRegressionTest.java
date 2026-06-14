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
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
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
import io.netty.util.concurrent.GlobalEventExecutor;
import org.asynchttpclient.AsyncHandler.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Protocol-conformance regression tests for the residual HTTP/2 review fixes:
 * <ul>
 *   <li><b>#6</b> 1xx interim responses (103 Early Hints) must NOT be delivered as the final status
 *       ({@code onStatusReceived} must fire exactly once, for the real response).</li>
 *   <li><b>#7</b> {@code Expect: 100-continue} over HTTP/2 must defer the body until the server's 100 and
 *       then send it (previously the resume mis-routed to the HTTP/1.1 writer and failed).</li>
 *   <li><b>#11</b> {@code TE} must be stripped unless its value is exactly {@code trailers} (RFC 9113 §8.2.2).</li>
 * </ul>
 */
public class Http2ConformanceRegressionTest {

    private NioEventLoopGroup serverGroup;
    private Channel serverChannel;
    private ChannelGroup serverChildChannels;
    private SslContext serverSslCtx;
    private int serverPort;

    @BeforeEach
    public void startServer() throws Exception {
        X509Bundle bundle = new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
        serverSslCtx = SslContextBuilder.forServer(bundle.toKeyManagerFactory())
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1))
                .build();
        serverGroup = new NioEventLoopGroup(1);
        serverChildChannels = new DefaultChannelGroup("h2-conformance", GlobalEventExecutor.INSTANCE);

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
                                        streamCh.pipeline().addLast(new ConformanceHandler());
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

    private String url(String path) {
        return "https://localhost:" + serverPort + path;
    }

    /**
     * Per-stream server behavior keyed on :path.
     * /earlyhints -> 103 (interim) then 200; /expect -> 100 on headers, 200 after the body DATA;
     * /te -> 200 echoing the received TE header in x-saw-te; anything else -> empty 200.
     */
    private static class ConformanceHandler extends SimpleChannelInboundHandler<Object> {
        private String path = "";

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame) {
                Http2Headers h = ((Http2HeadersFrame) msg).headers();
                CharSequence p = h.path();
                if (p != null) {
                    path = p.toString();
                }
                boolean endStream = ((Http2HeadersFrame) msg).isEndStream();
                if (path.contains("earlyhints")) {
                    ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("103"), false));
                    ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), true));
                } else if (path.contains("unsolicited100")) {
                    // Send an interim 100 even though the client did NOT send Expect: 100-continue. The real
                    // 200 is sent once the body DATA arrives (below). A correct client ignores the 100 (the
                    // body was already sent with endStream=true) and must NOT re-send the body.
                    ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("100"), false));
                    if (endStream) {
                        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), true));
                    }
                } else if (path.contains("expect")) {
                    // Send 100 Continue; the real 200 is sent once the body DATA arrives (below).
                    ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("100"), false));
                    if (endStream) {
                        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), true));
                    }
                } else if (path.contains("te")) {
                    CharSequence te = h.get("te");
                    ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()
                            .status("200").set("x-saw-te", te == null ? "none" : te.toString()), true));
                } else if (path.contains("authority")) {
                    CharSequence auth = h.authority();
                    ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()
                            .status("200").set("x-saw-authority", auth == null ? "none" : auth.toString()), true));
                } else if (endStream) {
                    ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), true));
                }
            } else if (msg instanceof Http2DataFrame && ((Http2DataFrame) msg).isEndStream()
                    && (path.contains("expect") || path.contains("unsolicited100"))) {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), true));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    private AsyncHttpClient client() {
        return asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setMaxConnectionsPerHost(1)
                .setRequestTimeout(Duration.ofSeconds(15)));
    }

    // #6 — a 103 Early Hints interim response must not be delivered as final.
    @Test
    public void interim1xxNotDeliveredAsFinalStatus() throws Exception {
        try (AsyncHttpClient client = client()) {
            AtomicInteger statusCount = new AtomicInteger(0);
            Integer finalStatus = client.prepareGet(url("/earlyhints")).execute(new AsyncHandler<Integer>() {
                private volatile int status;

                @Override
                public State onStatusReceived(HttpResponseStatus responseStatus) {
                    statusCount.incrementAndGet();
                    status = responseStatus.getStatusCode();
                    return State.CONTINUE;
                }

                @Override
                public State onHeadersReceived(HttpHeaders headers) {
                    return State.CONTINUE;
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
                    return State.CONTINUE;
                }

                @Override
                public void onThrowable(Throwable t) {
                }

                @Override
                public Integer onCompleted() {
                    return status;
                }
            }).get(10, SECONDS);

            assertEquals(200, finalStatus.intValue(), "final status must be the 200, not the 103");
            assertEquals(1, statusCount.get(),
                    "onStatusReceived must fire exactly once — a 103 Early Hints interim response must not be "
                            + "delivered as a status (it would fire twice, breaking the AsyncHandler contract).");
        }
    }

    // #7 — Expect: 100-continue over HTTP/2 must defer the body, then send it on the server's 100.
    @Test
    public void expect100ContinueOverHttp2SendsBodyAfterContinue() throws Exception {
        try (AsyncHttpClient client = client()) {
            Response r = client.preparePost(url("/expect"))
                    .setHeader("Expect", "100-continue")
                    .setBody("payload-after-continue")
                    .execute().get(10, SECONDS);
            // Old behavior: the body was sent eagerly and the 100 resume mis-routed to the HTTP/1.1 writer
            // (UnsupportedMessageTypeException), failing the request. The fix defers the body and sends it
            // on 100, so the server's DATA-triggered 200 comes back.
            assertEquals(200, r.getStatusCode(), "Expect/100-continue POST over HTTP/2 must complete with 200");
        }
    }

    // #11 — TE: gzip must be stripped; TE: trailers must be kept (RFC 9113 §8.2.2).
    @Test
    public void teHeaderStrippedUnlessTrailers() throws Exception {
        try (AsyncHttpClient client = client()) {
            Response stripped = client.prepareGet(url("/te")).setHeader("TE", "gzip").execute().get(10, SECONDS);
            assertEquals("none", stripped.getHeader("x-saw-te"),
                    "TE: gzip must be stripped before reaching the server (would otherwise trigger RST_STREAM)");

            Response kept = client.prepareGet(url("/te")).setHeader("TE", "trailers").execute().get(10, SECONDS);
            assertEquals("trailers", kept.getHeader("x-saw-te"), "TE: trailers is permitted and must be forwarded");
        }
    }

    // An unsolicited 100 (no Expect: 100-continue) over HTTP/2 must be ignored, not trigger a body re-send.
    @Test
    public void unsolicited100ContinueOverHttp2DoesNotResendBody() throws Exception {
        try (AsyncHttpClient client = client()) {
            // No Expect header, so the body is sent eagerly with endStream=true. The server then sends an
            // interim 100 before the 200. The old code re-sent the body on the 100 (sendHttp2RequestBody) —
            // a DATA frame after endStream on a half-closed (local) stream, a STREAM_CLOSED protocol error
            // (RFC 9113 §5.1) that fails the request. The fix ignores the unsolicited 100.
            Response r = client.preparePost(url("/unsolicited100"))
                    .setBody("body-without-expect")
                    .execute().get(10, SECONDS);
            assertEquals(200, r.getStatusCode(),
                    "an unsolicited 100 must be ignored (RFC 9110 §15.2.1); the body must not be re-sent");
        }
    }

    // :authority must NOT include the userinfo subcomponent (RFC 9113 §8.3.1).
    @Test
    public void authorityStripsUserInfo() throws Exception {
        try (AsyncHttpClient client = client()) {
            // An explicit Host header carrying userinfo (kept verbatim by the request factory and read for
            // :authority in sendHttp2Frames). Connection routing uses the URL, not this header, so it does
            // not affect where we connect. The server echoes the :authority it received; it must be
            // host[:port] only, with the "user:pass@" stripped.
            Response r = client.prepareGet(url("/authority"))
                    .setHeader("Host", "user:pass@localhost:" + serverPort)
                    .execute().get(10, SECONDS);
            assertEquals("localhost:" + serverPort, r.getHeader("x-saw-authority"),
                    ":authority must carry host[:port] with the userinfo subcomponent stripped (RFC 9113 §8.3.1)");
        }
    }
}
