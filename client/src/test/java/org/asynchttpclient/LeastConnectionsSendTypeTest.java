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

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.channel.Channel;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.NameResolver;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.proxy.ProxyType;
import org.asynchttpclient.testserver.SocksProxy;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.asynchttpclient.test.TestUtils.TIMEOUT;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.asynchttpclient.test.TestUtils.findFreePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end tests for {@link LoadBalance#LEAST_CONNECTIONS} over HTTP/1.1. The host resolves to several loopback
 * IPs, all served by one wildcard-bound server. The server can hold a request open until the test releases it, which
 * keeps that connection counted while the next one is opened.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LeastConnectionsSendTypeTest {

    private static final String IP1 = "127.0.0.1";
    private static final String IP2 = "127.0.0.2";
    private static final String IP3 = "127.0.0.3";

    private final Map<String, CountDownLatch> holds = new ConcurrentHashMap<>();
    private Server server;
    private int port;

    @BeforeAll
    public void start() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(new HoldingHandler());
        server.start();
        port = connector.getLocalPort();
    }

    @AfterAll
    public void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @AfterEach
    public void releaseHeldRequests() {
        holds.values().forEach(CountDownLatch::countDown);
        holds.clear();
    }

    // The query string that makes the server hold a request until release(id).
    private String hold(String id) {
        holds.put(id, new CountDownLatch(1));
        return "hold=" + id;
    }

    private void release(String id) {
        holds.get(id).countDown();
    }

    // Answers 200. With ?hold=<id> it first waits for the test to release that id, and with ?close it adds
    // Connection: close. A request that arrives after its test has ended finds no hold and is answered at once.
    private final class HoldingHandler extends AbstractHandler {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            String id = request.getParameter("hold");
            CountDownLatch held = id != null ? holds.get(id) : null;
            if (held != null) {
                try {
                    held.await(TIMEOUT, SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (request.getParameter("close") != null) {
                response.setHeader("Connection", "close");
            }
            response.setStatus(200);
            response.getOutputStream().close();
            baseRequest.setHandled(true);
        }
    }

    private static NameResolver<InetAddress> fixedResolver(String... ips) throws Exception {
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

    // macOS routes only 127.0.0.1 on its loopback interface; a connect to any other 127.x address times out there.
    private static boolean loopbackIsUsable(String ip) throws Exception {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(InetAddress.getByName(ip), 0));
            return true;
        } catch (IOException notOnThisHost) {
            return false;
        }
    }

    // Records the IPs one exchange targeted, in order, and the connection it opened.
    private static final class Recorder extends AsyncCompletionHandler<Response> {
        private final List<String> attempts = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Channel> connected = new CompletableFuture<>();

        @Override
        public void onTcpConnectAttempt(InetSocketAddress remoteAddress) {
            attempts.add(remoteAddress.getAddress().getHostAddress());
        }

        @Override
        public void onTcpConnectSuccess(InetSocketAddress remoteAddress, Channel connection) {
            connected.complete(connection);
        }

        @Override
        public Response onCompleted(Response response) {
            return response;
        }

        private String firstAttempt() {
            assertFalse(attempts.isEmpty(), "the exchange opened no connection");
            return attempts.get(0);
        }

        private String connectedIp() throws Exception {
            return ((InetSocketAddress) connected.get(TIMEOUT, SECONDS).remoteAddress()).getAddress().getHostAddress();
        }
    }

    private static AsyncHttpClientConfig leastConnections() {
        return config().setLoadBalance(LoadBalance.LEAST_CONNECTIONS).setMaxRequestRetry(0)
                .setConnectTimeout(Duration.ofSeconds(1)).build();
    }

    private static AsyncHttpClientConfig withoutCooldown() {
        return config().setLoadBalance(LoadBalance.LEAST_CONNECTIONS).setMaxRequestRetry(0)
                .setConnectTimeout(Duration.ofSeconds(1)).setFailedIpCooldownEnabled(false).build();
    }

    // Returns once the connection has closed and its count is released. The release runs in a close listener on the
    // connection's event loop.
    private static void awaitClosed(Channel channel) throws Exception {
        assertTrue(channel.closeFuture().await(TIMEOUT, SECONDS), "the connection did not close");
        channel.eventLoop().submit(() -> {
        }).get(TIMEOUT, SECONDS);
    }

    private static String url(int serverPort, String query) {
        return "http://lc.test:" + serverPort + "/" + (query.isEmpty() ? "" : "?" + query);
    }

    private static Response send(AsyncHttpClient client, String url, NameResolver<InetAddress> resolver, Recorder recorder)
            throws Exception {
        return client.executeRequest(get(url).setNameResolver(resolver), recorder).get(TIMEOUT, SECONDS);
    }

    @Test
    public void reusesThePooledConnectionInsteadOfOpeningAnother() throws Exception {
        NameResolver<InetAddress> resolver = fixedResolver(IP1, IP2, IP3);
        List<String> attempts = new ArrayList<>();
        try (AsyncHttpClient client = asyncHttpClient(leastConnections())) {
            for (int i = 0; i < 10; i++) {
                Recorder recorder = new Recorder();
                assertEquals(200, send(client, url(port, ""), resolver, recorder).getStatusCode());
                attempts.addAll(recorder.attempts);
            }
        }
        assertEquals(List.of(IP1), attempts, "one connection, to the first address, reused by every later request");
    }

    @Test
    public void concurrentRequestsSpreadTheirConnectionsAcrossTheAddresses() throws Exception {
        boolean allUsable = loopbackIsUsable(IP2) && loopbackIsUsable(IP3);
        Map<String, Integer> firstAttempts = new HashMap<>();
        Map<String, Integer> connectedIps = new HashMap<>();
        burst(leastConnections(), firstAttempts, connectedIps, allUsable);
        // A pick is counted before its connect starts, so six back-to-back connects land two on each address.
        assertEquals(Map.of(IP1, 2, IP2, 2, IP3, 2), firstAttempts);
        if (allUsable) {
            assertEquals(Map.of(IP1, 2, IP2, 2, IP3, 2), connectedIps);
        }

        firstAttempts.clear();
        connectedIps.clear();
        burst(config().setMaxRequestRetry(0).build(), firstAttempts, connectedIps, true);
        assertEquals(Map.of(IP1, 6), firstAttempts, "DEFAULT mode keeps targeting the first address");
        assertEquals(Map.of(IP1, 6), connectedIps);
    }

    private void burst(AsyncHttpClientConfig config, Map<String, Integer> firstAttempts, Map<String, Integer> connectedIps,
                       boolean awaitConnections) throws Exception {
        NameResolver<InetAddress> resolver = fixedResolver(IP1, IP2, IP3);
        String query = hold("burst");
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            List<Recorder> recorders = new ArrayList<>();
            List<ListenableFuture<Response>> responses = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Recorder recorder = new Recorder();
                recorders.add(recorder);
                responses.add(client.executeRequest(get(url(port, query)).setNameResolver(resolver), recorder));
            }
            for (Recorder recorder : recorders) {
                firstAttempts.merge(recorder.firstAttempt(), 1, Integer::sum);
                if (awaitConnections) {
                    connectedIps.merge(recorder.connectedIp(), 1, Integer::sum);
                }
            }
            release("burst");
            for (ListenableFuture<Response> response : responses) {
                assertEquals(200, response.get(TIMEOUT, SECONDS).getStatusCode());
            }
        }
    }

    @Test
    public void aClosedConnectionStopsCounting() throws Exception {
        assumeTrue(loopbackIsUsable(IP2), IP2 + " is not a usable loopback address on this host");
        NameResolver<InetAddress> resolver = fixedResolver(IP1, IP2);
        try (AsyncHttpClient client = asyncHttpClient(leastConnections())) {
            Recorder first = new Recorder();
            client.executeRequest(get(url(port, hold("first"))).setNameResolver(resolver), first);
            assertEquals(IP1, first.connectedIp());

            Recorder closing = new Recorder();
            ListenableFuture<Response> closed = client.executeRequest(
                    get(url(port, hold("closing") + "&close=true")).setNameResolver(resolver), closing);
            assertEquals(IP2, closing.connectedIp());
            Channel closingChannel = closing.connected.get(TIMEOUT, SECONDS);
            release("closing");
            assertEquals(200, closed.get(TIMEOUT, SECONDS).getStatusCode());
            awaitClosed(closingChannel);

            // 127.0.0.1 still holds the first request; 127.0.0.2 has nothing left open.
            Recorder next = new Recorder();
            client.executeRequest(get(url(port, hold("next"))).setNameResolver(resolver), next);
            assertEquals(IP2, next.firstAttempt(), "the closed connection must no longer count against 127.0.0.2");
            assertEquals(IP2, next.connectedIp());
        }
    }

    @Test
    public void theFailedIpCooldownOutranksTheCount() throws Exception {
        Server local = new Server();
        ServerConnector connector = addHttpConnector(local);
        connector.setHost(IP1);
        local.setHandler(new HoldingHandler());
        local.start();
        try {
            // Nothing listens on 127.0.0.2 at this port: refused on Linux and Windows, unreachable on macOS.
            NameResolver<InetAddress> resolver = fixedResolver(IP2, IP1);
            try (AsyncHttpClient client = asyncHttpClient(leastConnections())) {
                Recorder first = new Recorder();
                client.executeRequest(get(url(connector.getLocalPort(), hold("first"))).setNameResolver(resolver), first);
                assertEquals(IP1, first.connectedIp());
                assertEquals(List.of(IP2, IP1), first.attempts, "all at zero, so the resolver's first, then failover");

                // 127.0.0.2 has no connections and 127.0.0.1 has one, but 127.0.0.2 is cooling after its failure.
                Recorder second = new Recorder();
                client.executeRequest(get(url(connector.getLocalPort(), hold("second"))).setNameResolver(resolver), second);
                assertEquals(IP1, second.connectedIp());
                assertEquals(List.of(IP1), second.attempts);
            }
        } finally {
            releaseHeldRequests();
            local.stop();
        }
    }

    @Test
    public void withoutTheCooldownAFailedAddressStaysUsableAndIsTriedFirstAgain() throws Exception {
        Server local = new Server();
        ServerConnector connector = addHttpConnector(local);
        connector.setHost(IP1);
        local.setHandler(new HoldingHandler());
        local.start();
        try {
            NameResolver<InetAddress> resolver = fixedResolver(IP2, IP1);
            try (AsyncHttpClient client = asyncHttpClient(withoutCooldown())) {
                for (int i = 0; i < 3; i++) {
                    Recorder recorder = new Recorder();
                    send(client, url(connector.getLocalPort(), "close=true"), resolver, recorder);
                    assertEquals(IP1, recorder.connectedIp(), "request " + i + " fails over to the live address");
                    // Both addresses are back at zero only if the failed attempt released its count. A leaked
                    // count would put 127.0.0.1 first next time.
                    assertEquals(List.of(IP2, IP1), recorder.attempts, "request " + i);
                    awaitClosed(recorder.connected.get(TIMEOUT, SECONDS));
                }
            }
        } finally {
            local.stop();
        }
    }

    @Test
    public void aFailoverGoesToTheLeastConnectedOfTheAddressesNotTriedYet() throws Exception {
        assumeTrue(loopbackIsUsable(IP3), IP3 + " is not a usable loopback address on this host");
        // 127.0.0.1 and 127.0.0.3 listen on the same port; nothing does on 127.0.0.2.
        int localPort = findFreePort();
        Server local = new Server();
        for (String ip : new String[]{IP1, IP3}) {
            ServerConnector connector = addHttpConnector(local);
            connector.setHost(ip);
            connector.setPort(localPort);
        }
        local.setHandler(new HoldingHandler());
        local.start();
        try {
            NameResolver<InetAddress> resolver = fixedResolver(IP2, IP1, IP3);
            Map<String, Integer> connectedIps = new HashMap<>();
            try (AsyncHttpClient client = asyncHttpClient(withoutCooldown())) {
                for (int i = 0; i < 6; i++) {
                    Recorder recorder = new Recorder();
                    client.executeRequest(get(url(localPort, hold("r" + i))).setNameResolver(resolver), recorder);
                    connectedIps.merge(recorder.connectedIp(), 1, Integer::sum);
                    assertEquals(IP2, recorder.firstAttempt(), "with no cooldown the refused address has no connections");
                }
            }
            assertEquals(Map.of(IP1, 3, IP3, 3), connectedIps, "each failover goes to the live address with fewer");
        } finally {
            releaseHeldRequests();
            local.stop();
        }
    }

    @Test
    public void proxiedRequestsKeepTheResolverOrder() throws Exception {
        NameResolver<InetAddress> resolver = fixedResolver(IP1, IP2);
        try (AsyncHttpClient client = asyncHttpClient(leastConnections())) {
            Recorder held = new Recorder();
            client.executeRequest(get(url(port, hold("held"))).setNameResolver(resolver), held);
            assertEquals(IP1, held.connectedIp());

            // The same server doubles as a proxy whose name resolves to the same two addresses. 127.0.0.1 already
            // has a connection, but proxied connections are not balanced.
            Recorder proxied = new Recorder();
            Response response = client.executeRequest(get("http://target.test/").setNameResolver(resolver)
                    .setProxyServer(proxyServer("proxy.test", port)), proxied).get(TIMEOUT, SECONDS);
            assertEquals(200, response.getStatusCode());
            assertEquals(IP1, proxied.firstAttempt());
        }
    }

    @Test
    public void connectionsToSingleAddressTargetsAreNotCounted() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(leastConnections())) {
            Recorder literal = new Recorder();
            client.executeRequest(get("http://" + IP1 + ":" + port + "/?" + hold("literal")), literal);
            Recorder single = new Recorder();
            client.executeRequest(get("http://single.test:" + port + "/?" + hold("single"))
                    .setNameResolver(fixedResolver(IP1)), single);
            Recorder explicit = new Recorder();
            client.executeRequest(get("http://explicit.test:" + port + "/?" + hold("explicit"))
                    .setNameResolver(fixedResolver(IP2, IP1)).setAddress(InetAddress.getByName(IP1)), explicit);
            for (Recorder recorder : List.of(literal, single, explicit)) {
                assertEquals(IP1, recorder.connectedIp());
                assertEquals(List.of(IP1), recorder.attempts);
            }

            // Three connections are open to 127.0.0.1, none of them counted, so it still wins the tie.
            Recorder balanced = new Recorder();
            client.executeRequest(get(url(port, hold("balanced"))).setNameResolver(fixedResolver(IP1, IP2)), balanced);
            assertEquals(IP1, balanced.firstAttempt());
        }
    }

    @Test
    public void httpsThroughAnHttpProxyIsNotBalanced() throws Exception {
        Server proxy = new Server();
        ServerConnector proxyConnector = addHttpConnector(proxy);
        ConnectHandler connectHandler = new ConnectHandler();
        connectHandler.setHandler(new HoldingHandler());
        proxy.setHandler(connectHandler);
        Server target = new Server();
        ServerConnector targetConnector = addHttpsConnector(target);
        target.setHandler(new HoldingHandler());
        proxy.start();
        target.start();
        try {
            int proxyPort = proxyConnector.getLocalPort();
            NameResolver<InetAddress> resolver = fixedResolver(IP1, IP2);
            AsyncHttpClientConfig config = config().setLoadBalance(LoadBalance.LEAST_CONNECTIONS).setMaxRequestRetry(0)
                    .setConnectTimeout(Duration.ofSeconds(1)).setUseInsecureTrustManager(true).build();
            try (AsyncHttpClient client = asyncHttpClient(config)) {
                // A direct connection to the proxy's port on 127.0.0.1, so a balanced connection would avoid it.
                Recorder held = new Recorder();
                client.executeRequest(get(url(proxyPort, hold("held"))).setNameResolver(resolver), held);
                assertEquals(IP1, held.connectedIp());

                Recorder tunnelled = new Recorder();
                Response response = client.executeRequest(get("https://" + IP1 + ":" + targetConnector.getLocalPort() + "/")
                        .setNameResolver(resolver).setProxyServer(proxyServer("proxy.test", proxyPort)), tunnelled)
                        .get(TIMEOUT, SECONDS);
                assertEquals(200, response.getStatusCode());
                assertEquals(IP1, tunnelled.firstAttempt(), "the CONNECT hop keeps the resolver's order");
            }
        } finally {
            releaseHeldRequests();
            target.stop();
            proxy.stop();
        }
    }

    @Test
    public void aSocksProxiedRequestKeepsTheResolverOrder() throws Exception {
        SocksProxy socksProxy = new SocksProxy(60000);
        Thread proxyLoop = new Thread(() -> {
            try {
                socksProxy.run();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        proxyLoop.start();
        NameResolver<InetAddress> resolver = fixedResolver(IP1, IP2);
        try (AsyncHttpClient client = asyncHttpClient(leastConnections())) {
            Recorder held = new Recorder();
            client.executeRequest(get(url(port, hold("held"))).setNameResolver(resolver), held);
            assertEquals(IP1, held.connectedIp());

            // The SOCKS hop reaches the host's own addresses through the proxy and is not balanced either. The
            // test proxy speaks SOCKS4 only.
            Recorder proxied = new Recorder();
            Response response = client.executeRequest(get(url(port, "")).setNameResolver(resolver)
                    .setProxyServer(proxyServer("socks.test", socksProxy.getPort()).setProxyType(ProxyType.SOCKS_V4)),
                    proxied).get(TIMEOUT, SECONDS);
            assertEquals(200, response.getStatusCode());
            assertEquals(IP1, proxied.firstAttempt());
        } finally {
            socksProxy.stop();
            proxyLoop.join(TIMEOUT * 1000L);
        }
    }

    @Test
    public void aRequestThatBypassesTheProxyIsBalanced() throws Exception {
        NameResolver<InetAddress> resolver = fixedResolver(IP1, IP2);
        try (AsyncHttpClient client = asyncHttpClient(leastConnections())) {
            Recorder held = new Recorder();
            client.executeRequest(get(url(port, hold("held"))).setNameResolver(resolver), held);
            assertEquals(IP1, held.connectedIp());

            Recorder bypassing = new Recorder();
            client.executeRequest(get(url(port, hold("bypassing"))).setNameResolver(resolver)
                    .setProxyServer(proxyServer("proxy.test", 1).setNonProxyHosts(List.of("lc.test"))), bypassing);
            assertEquals(IP2, bypassing.firstAttempt(), "a bypassed proxy leaves a direct connection, which is balanced");
        }
    }
}
