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
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
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
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.HttpProtocol;
import org.asynchttpclient.Response;
import org.asynchttpclient.testserver.SocksProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * HTTP/2 across a forward proxy, against proxies written at the socket level rather than through Jetty.
 * <p>
 * Two hops can negotiate ALPN on a proxied HTTPS request and they have opposite requirements. The hop that
 * ends at the <b>origin</b> — set up after the {@code CONNECT} is accepted — must be able to negotiate h2,
 * or the request goes out as HTTP/1.1 on a connection the origin agreed to speak HTTP/2 on and fails with
 * {@code InvalidLineSeparatorException} (issues #2241, #2311). The hop that ends at an <b>HTTPS proxy</b>
 * must never negotiate h2, because everything AsyncHttpClient writes there is HTTP/1.1.
 * <p>
 * {@code BasicHttp2Test} covers the origin hop through Jetty's {@code ConnectHandler}; the proxies here
 * answer byte-for-byte like tinyproxy and like an h2-capable TLS proxy listener, which no Jetty-based test
 * reproduces.
 */
public class ProxyTunnelHttp2Test {

    private NioEventLoopGroup serverGroup;
    private Channel serverChannel;
    private ChannelGroup serverChildChannels;
    private SslContext serverSslCtx;
    private int serverPort;

    private ConnectProxy proxy;
    private X509Bundle proxyBundle;

    /** A raw {@code CONNECT} proxy that answers exactly like tinyproxy 1.11.2's {@code send_ssl_response()}. */
    private static class ConnectProxy implements AutoCloseable {

        final ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "connect-proxy");
            t.setDaemon(true);
            return t;
        });
        private final ServerSocket serverSocket;
        private final List<String> connectLines = new CopyOnWriteArrayList<>();
        private final AtomicInteger acceptedConnections = new AtomicInteger();
        volatile boolean closed;

        ConnectProxy(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
        }

        /**
         * Starts accepting. Kept out of the constructor: the accept thread calls the overridable
         * {@link #handle(Socket)}, which a subclass must be fully constructed to serve.
         */
        void start() {
            pool.submit(this::acceptLoop);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        List<String> connectLines() {
            return Collections.unmodifiableList(connectLines);
        }

        int acceptedConnections() {
            return acceptedConnections.get();
        }

        private void acceptLoop() {
            while (!closed) {
                try {
                    Socket client = serverSocket.accept();
                    acceptedConnections.incrementAndGet();
                    pool.submit(() -> {
                        try {
                            handle(client);
                        } catch (Exception e) {
                            if (!closed) {
                                e.printStackTrace();
                            }
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        }

        /** Reads the CONNECT head, opens the upstream socket and pipes both directions. */
        void handle(Socket client) throws Exception {
            client.setTcpNoDelay(true);
            tunnel(client, client.getInputStream(), client.getOutputStream(),
                    "HTTP/1.0 200 Connection established\r\nProxy-agent: tinyproxy/1.11.2\r\n\r\n");
        }

        final void tunnel(Socket client, InputStream in, OutputStream out, String establishedResponse) throws IOException {
            String requestLine = readLine(in);
            if (requestLine == null) {
                client.close();
                return;
            }
            connectLines.add(requestLine);
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                // discard the rest of the head
            }

            String[] parts = requestLine.split(" ");
            if (!"CONNECT".equals(parts[0])) {
                client.close();
                return;
            }
            String[] hostPort = parts[1].split(":");
            Socket upstream = new Socket(hostPort[0], Integer.parseInt(hostPort[1]));
            upstream.setTcpNoDelay(true);

            out.write(establishedResponse.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            OutputStream upstreamOut = upstream.getOutputStream();
            InputStream upstreamIn = upstream.getInputStream();
            pool.submit(() -> pipe(in, upstreamOut, upstream, client));
            pipe(upstreamIn, out, upstream, client);
        }

        static void pipe(InputStream from, OutputStream to, Socket a, Socket b) {
            byte[] buf = new byte[16 * 1024];
            try {
                int read;
                while ((read = from.read(buf)) != -1) {
                    to.write(buf, 0, read);
                    to.flush();
                }
            } catch (IOException ignored) {
                // connection torn down
            } finally {
                closeQuietly(a);
                closeQuietly(b);
            }
        }

        static void closeQuietly(Socket s) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }

        static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    int len = sb.length();
                    if (len > 0 && sb.charAt(len - 1) == '\r') {
                        sb.setLength(len - 1);
                    }
                    return sb.toString();
                }
                sb.append((char) c);
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            serverSocket.close();
            pool.shutdownNow();
        }
    }

    /**
     * A TLS {@code CONNECT} proxy whose listener advertises {@code h2} ahead of {@code http/1.1}, like any
     * HTTP/2-capable forward proxy. Records what each connection negotiated, and behaves like a real h2
     * endpoint when h2 is selected: it demands the client connection preface (RFC 9113 §3.4) and drops a
     * connection that opens with anything else.
     */
    private static final class TlsConnectProxy extends ConnectProxy {

        private static final int H2_PREFACE_LENGTH = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".length();

        private final List<String> negotiatedProtocols = new CopyOnWriteArrayList<>();

        TlsConnectProxy(X509Bundle bundle) throws Exception {
            super(newTlsServerSocket(bundle));
        }

        static TlsConnectProxy started(X509Bundle bundle) throws Exception {
            TlsConnectProxy proxy = new TlsConnectProxy(bundle);
            proxy.start();
            return proxy;
        }

        private static ServerSocket newTlsServerSocket(X509Bundle bundle) throws Exception {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(bundle.toKeyManagerFactory().getKeyManagers(), null, null);
            return ctx.getServerSocketFactory().createServerSocket(0);
        }

        List<String> negotiatedProtocols() {
            return Collections.unmodifiableList(negotiatedProtocols);
        }

        @Override
        void handle(Socket socket) throws Exception {
            SSLSocket client = (SSLSocket) socket;
            SSLParameters params = client.getSSLParameters();
            params.setApplicationProtocols(new String[]{ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1});
            client.setSSLParameters(params);
            client.startHandshake();
            String alpn = client.getApplicationProtocol();
            negotiatedProtocols.add(alpn == null ? "" : alpn);

            InputStream in = client.getInputStream();
            if (ApplicationProtocolNames.HTTP_2.equals(alpn)) {
                byte[] preface = new byte[H2_PREFACE_LENGTH];
                int read = 0;
                while (read < preface.length) {
                    int n = in.read(preface, read, preface.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                // Whatever arrived, it was not an HTTP/2 preface: connection error, drop it.
                client.close();
                return;
            }
            tunnel(client, in, client.getOutputStream(), "HTTP/1.1 200 Connection established\r\n\r\n");
        }
    }

    /** Echoes the method and {@code :authority} of whatever the client sent, as HTTP/2. */
    private static final class Http2TestServerHandler extends SimpleChannelInboundHandler<Object> {

        private Http2Headers requestHeaders;

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
            String authority = requestHeaders.authority() != null ? requestHeaders.authority().toString() : "?";
            ByteBuf body = Unpooled.copiedBuffer(method + ' ' + authority, StandardCharsets.UTF_8);
            ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
            ctx.writeAndFlush(new DefaultHttp2DataFrame(body, true));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    @BeforeEach
    public void startServers() throws Exception {
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
        serverChildChannels = new DefaultChannelGroup("proxy-tunnel-http2-server", GlobalEventExecutor.INSTANCE);

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
        serverPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();

        proxy = new ConnectProxy(new ServerSocket(0));
        proxy.start();
        proxyBundle = new CertificateBuilder()
                .subject("CN=localhost")
                .setIsCertificateAuthority(true)
                .buildSelfSigned();
    }

    @AfterEach
    public void stopServers() throws Exception {
        if (proxy != null) {
            proxy.close();
        }
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

    private AsyncHttpClient clientThrough(ConnectProxy connectProxy) {
        return asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setProxyServer(proxyServer("localhost", connectProxy.port())));
    }

    /**
     * Issues #2241 / #2311: the target hop negotiated h2, so the tunneled request has to go out as HTTP/2
     * frames. Before the fix this wrote an HTTP/1.1 request line and failed with
     * {@code InvalidLineSeparatorException} while the origin reported a bad HTTP/2 preface.
     */
    @Test
    public void httpsThroughRawConnectProxyUsesHttp2() throws Exception {
        try (AsyncHttpClient client = clientThrough(proxy)) {
            Response response = client.prepareOptions(httpsUrl("/anything")).execute().get(30, SECONDS);
            assertNotNull(response);
            assertEquals(200, response.getStatusCode());
            assertEquals(HttpProtocol.HTTP_2, response.getProtocol());
            assertEquals("OPTIONS localhost:" + serverPort, response.getResponseBody());
            assertEquals(1, proxy.acceptedConnections());
        }
    }

    /** The upgraded tunnel is registered as an HTTP/2 connection, so a second request multiplexes onto it. */
    @Test
    public void rawConnectProxyTunnelIsReused() throws Exception {
        try (AsyncHttpClient client = clientThrough(proxy)) {
            Response first = client.prepareGet(httpsUrl("/one")).execute().get(30, SECONDS);
            Response second = client.prepareGet(httpsUrl("/two")).execute().get(30, SECONDS);

            assertEquals(200, first.getStatusCode());
            assertEquals(200, second.getStatusCode());
            assertEquals(HttpProtocol.HTTP_2, first.getProtocol());
            assertEquals(HttpProtocol.HTTP_2, second.getProtocol());
            assertEquals(1, serverChildChannels.size(), "the tunnel should be reused, not re-established");
            assertEquals(1, proxy.connectLines().size(), "one CONNECT expected, got " + proxy.connectLines());
        }
    }

    /** Requests issued before any tunnel exists all complete over HTTP/2. */
    @Test
    public void concurrentRequestsThroughProxyUseHttp2() throws Exception {
        try (AsyncHttpClient client = clientThrough(proxy)) {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(client.prepareGet(httpsUrl("/c" + i)).execute());
            }
            for (Future<Response> f : futures) {
                Response response = f.get(30, SECONDS);
                assertEquals(200, response.getStatusCode());
                assertEquals(HttpProtocol.HTTP_2, response.getProtocol());
            }
        }
    }

    /**
     * The hop to an HTTPS proxy carries an HTTP/1.1 {@code CONNECT}, so it must advertise only http/1.1 —
     * whatever the target hop is allowed to negotiate. Advertising h2 lets an HTTP/2-capable proxy select it
     * and then receive HTTP/1.1 bytes, which it answers by dropping the connection.
     */
    @Test
    public void httpsProxyHopMustNotNegotiateHttp2() throws Exception {
        try (TlsConnectProxy tlsProxy = TlsConnectProxy.started(proxyBundle)) {
            Throwable failure = null;
            Response response = null;
            try (AsyncHttpClient client = asyncHttpClient(config()
                    .setUseInsecureTrustManager(true)
                    .setProxyServer(proxyServer("localhost", tlsProxy.port()).setProxyType(ProxyType.HTTPS)))) {
                try {
                    response = client.prepareGet(httpsUrl("/through-tls-proxy")).execute().get(30, SECONDS);
                } catch (Exception e) {
                    failure = e;
                }
            }
            List<String> negotiated = tlsProxy.negotiatedProtocols();
            assertFalse(negotiated.isEmpty(), "the proxy was never reached");
            assertEquals(Collections.singleton(ApplicationProtocolNames.HTTP_1_1), new HashSet<>(negotiated),
                    "AsyncHttpClient speaks HTTP/1.1 to a proxy and must advertise only http/1.1 on that hop"
                            + " (request outcome: " + (failure != null ? failure : response) + ')');
            assertNotNull(response, "request should succeed once the proxy hop stays on HTTP/1.1");
            assertEquals(200, response.getStatusCode());
            assertEquals(HttpProtocol.HTTP_2, response.getProtocol(), "the target hop should still be HTTP/2");
        }
    }

    /**
     * SOCKS carries the TLS handshake straight through to the origin, so the ordinary direct-connect ALPN
     * check applies and the connection must come up on HTTP/2.
     */
    @Test
    public void socksProxyTargetNegotiatesHttp2() throws Exception {
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
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setProxyServer(proxyServer("localhost", socks.getPort()).setProxyType(ProxyType.SOCKS_V4)))) {
            Response response = client.prepareGet(httpsUrl("/through-socks")).execute().get(30, SECONDS);
            assertEquals(200, response.getStatusCode());
            assertEquals(HttpProtocol.HTTP_2, response.getProtocol());
        } finally {
            socks.stop();
        }
    }

    /**
     * Multiplexing takes no connection permit, so a second request must ride the existing tunnel rather than
     * stall on {@code maxConnectionsPerHost}.
     */
    @Test
    public void proxyHttp2MultiplexesUnderMaxConnectionsPerHost() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setMaxConnectionsPerHost(1)
                .setAcquireFreeChannelTimeout(500)
                .setProxyServer(proxyServer("localhost", proxy.port())))) {
            Response first = client.prepareGet(httpsUrl("/p1")).execute().get(30, SECONDS);
            Response second = client.prepareGet(httpsUrl("/p2")).execute().get(30, SECONDS);

            assertEquals(200, first.getStatusCode());
            assertEquals(200, second.getStatusCode());
            assertEquals(HttpProtocol.HTTP_2, first.getProtocol());
            assertEquals(HttpProtocol.HTTP_2, second.getProtocol());
        }
    }
}
