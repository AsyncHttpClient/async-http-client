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
package org.asynchttpclient.proxy;

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
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.HttpProtocol;
import org.asynchttpclient.Response;
import org.asynchttpclient.testserver.SocksProxy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cleartext HTTP/2 (prior knowledge) and proxies.
 * <p>
 * Prior knowledge is knowledge about the <em>origin</em>, so it may only be acted on when the origin is what
 * is on the other end of the socket. Behind an HTTP forward proxy it is not: a cleartext target needs no
 * {@code CONNECT}, so the request goes to the proxy in absolute-URI HTTP/1.1 form. Behind a SOCKS proxy it
 * is, because SOCKS tunnels at the transport layer — which is why the guard cannot simply be
 * {@code proxyServer == null}.
 */
public class ProxyH2cTest {

    /** Echoes the method and {@code :authority} of whatever the client sent, over cleartext HTTP/2. */
    private static final class H2cHandler extends SimpleChannelInboundHandler<Object> {

        private io.netty.handler.codec.http2.Http2Headers requestHeaders;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame) {
                Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                requestHeaders = headersFrame.headers();
                if (headersFrame.isEndStream()) {
                    respond(ctx);
                }
            } else if (msg instanceof Http2DataFrame && ((Http2DataFrame) msg).isEndStream()) {
                respond(ctx);
            }
        }

        private void respond(ChannelHandlerContext ctx) {
            String method = requestHeaders.method() != null ? requestHeaders.method().toString() : "?";
            ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
            ctx.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.copiedBuffer(method, StandardCharsets.UTF_8), true));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /**
     * An HTTP forward proxy stub that records the first line it is sent and hangs up. Enough to see whether
     * AsyncHttpClient opened with an HTTP/1.1 request or with the HTTP/2 connection preface.
     */
    @Test
    public void h2cIsNotSpokenToAnHttpProxy() throws Exception {
        List<String> firstLines = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(1);
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "h2c-probe-proxy");
            t.setDaemon(true);
            return t;
        });

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            pool.submit(() -> {
                try (Socket client = serverSocket.accept()) {
                    InputStream in = client.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    int c;
                    while ((c = in.read()) != -1 && c != '\n') {
                        if (c != '\r') {
                            sb.append((char) c);
                        }
                    }
                    firstLines.add(sb.toString());
                } catch (IOException ignored) {
                    // the client hangs up as soon as we do
                } finally {
                    received.countDown();
                }
            });

            try (AsyncHttpClient client = asyncHttpClient(config()
                    .setHttp2CleartextEnabled(true)
                    .setProxyServer(proxyServer("localhost", serverSocket.getLocalPort())))) {
                try {
                    client.prepareGet("http://example.invalid:8080/probe").execute().get(10, TimeUnit.SECONDS);
                } catch (Exception expected) {
                    // the stub proxy answers nothing; only the request head matters
                }
            }

            assertTrue(received.await(10, TimeUnit.SECONDS), "the proxy never saw a request");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, firstLines.size());
        assertEquals("GET http://example.invalid:8080/probe HTTP/1.1", firstLines.get(0),
                "AsyncHttpClient must open an HTTP/1.1 exchange with an HTTP proxy, not an HTTP/2 preface");
    }

    /**
     * SOCKS tunnels at the transport layer, so the peer of everything written after the SOCKS handshake is
     * the origin: h2c prior knowledge still applies and must not be disabled along with the HTTP-proxy case.
     */
    @Test
    public void h2cIsSpokenThroughASocksProxy() throws Exception {
        NioEventLoopGroup serverGroup = new NioEventLoopGroup(1);
        Channel serverChannel = null;
        SocksProxy socks = new SocksProxy(30_000);
        Thread socksThread = new Thread(() -> {
            try {
                socks.run();
            } catch (IOException ignored) {
                // shutting down
            }
        });
        socksThread.setDaemon(true);
        socksThread.start();

        try {
            serverChannel = new ServerBootstrap()
                    .group(serverGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline()
                                    .addLast(Http2FrameCodecBuilder.forServer().build())
                                    .addLast(new Http2MultiplexHandler(new ChannelInitializer<Http2StreamChannel>() {
                                        @Override
                                        protected void initChannel(Http2StreamChannel streamCh) {
                                            streamCh.pipeline().addLast(new H2cHandler());
                                        }
                                    }));
                        }
                    })
                    .bind(0).sync().channel();
            int serverPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();

            try (AsyncHttpClient client = asyncHttpClient(config()
                    .setHttp2CleartextEnabled(true)
                    .setProxyServer(proxyServer("localhost", socks.getPort()).setProxyType(ProxyType.SOCKS_V4)))) {
                Response response = client.prepareGet("http://localhost:" + serverPort + "/probe")
                        .execute().get(30, TimeUnit.SECONDS);
                assertEquals(200, response.getStatusCode());
                assertEquals(HttpProtocol.HTTP_2, response.getProtocol());
                assertEquals("GET", response.getResponseBody());
            }
        } finally {
            socks.stop();
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
            serverGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).sync();
        }
    }
}
