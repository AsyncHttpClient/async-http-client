/*
 *    Copyright (c) 2015-2026 AsyncHttpClient Project. All rights reserved.
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

import io.netty.channel.Channel;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.NameResolver;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.test.EchoHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.test.TestUtils.TIMEOUT;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end coverage for {@link LoadBalance#ROUND_ROBIN}: a single host that resolves to several
 * loopback IPs (all served by one wildcard-bound test server) should have its requests spread across
 * every IP, whereas {@link LoadBalance#DEFAULT} keeps reusing a single pooled connection.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RoundRobinSendTypeTest {

    private static final String[] IPS = {"127.0.0.1", "127.0.0.2", "127.0.0.3"};

    private Server server;
    private int port;

    @BeforeAll
    public void start() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server); // binds to the wildcard, so all loopback IPs reach it
        server.setHandler(new EchoHandler());
        server.start();
        port = connector.getLocalPort();
    }

    @AfterAll
    public void stop() throws Exception {
        if (server != null) {
            server.stop();
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

    private static final class ConnectRecorder extends AsyncCompletionHandler<Response> {
        private final Set<String> attemptedIps;
        private final Set<String> connectedIps;

        private ConnectRecorder(Set<String> attemptedIps, Set<String> connectedIps) {
            this.attemptedIps = attemptedIps;
            this.connectedIps = connectedIps;
        }

        // onTcpConnectAttempt fires for every IP the connector targets, whether or not it is reachable.
        // We assert on this rather than onTcpConnectSuccess so the test is portable: on macOS only
        // 127.0.0.1 is a usable loopback address, so the other IPs are targeted but fail over to it.
        @Override
        public void onTcpConnectAttempt(InetSocketAddress remoteAddress) {
            record(attemptedIps, remoteAddress);
        }

        @Override
        public void onTcpConnectSuccess(InetSocketAddress remoteAddress, Channel connection) {
            record(connectedIps, remoteAddress);
        }

        private static void record(Set<String> set, InetSocketAddress address) {
            if (address != null && address.getAddress() != null) {
                set.add(address.getAddress().getHostAddress());
            }
        }

        @Override
        public Response onCompleted(Response response) {
            return response;
        }
    }

    // Records the IPs a single request targeted, in the order the connector attempted them, so a test can
    // assert which IP a new connection tried first (the failed-IP cooldown re-orders that first choice).
    private static final class OrderedAttemptRecorder extends AsyncCompletionHandler<Response> {
        private final List<String> attemptedIps;

        private OrderedAttemptRecorder(List<String> attemptedIps) {
            this.attemptedIps = attemptedIps;
        }

        @Override
        public void onTcpConnectAttempt(InetSocketAddress remoteAddress) {
            if (remoteAddress != null && remoteAddress.getAddress() != null) {
                attemptedIps.add(remoteAddress.getAddress().getHostAddress());
            }
        }

        @Override
        public Response onCompleted(Response response) {
            return response;
        }
    }

    // Returns the set of IPs the client targeted (first choice plus any failovers) across the requests.
    private Set<String> runRequestsCapturingTargetedIps(AsyncHttpClientConfig config) throws Exception {
        Set<String> attemptedIps = ConcurrentHashMap.newKeySet();
        Set<String> connectedIps = ConcurrentHashMap.newKeySet();
        NameResolver<InetAddress> resolver = fixedResolver(IPS);
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            for (int i = 0; i < 12; i++) {
                Response response = client.executeRequest(
                        get("http://roundrobin.test:" + port + "/").setNameResolver(resolver),
                        new ConnectRecorder(attemptedIps, connectedIps)).get(TIMEOUT, SECONDS);
                assertEquals(200, response.getStatusCode());
            }
        }
        return attemptedIps;
    }

    @Test
    public void roundRobinSpreadsConnectionsAcrossAllIps() throws Exception {
        Set<String> targetedIps = runRequestsCapturingTargetedIps(config().setLoadBalance(LoadBalance.ROUND_ROBIN).setMaxRequestRetry(0).build());
        // every resolved IP gets its own pool partition, so round-robin targets a fresh connection per IP
        assertEquals(Set.of(IPS), targetedIps, "round-robin should target every resolved IP");
    }

    @Test
    public void defaultModeStaysOnASingleIp() throws Exception {
        Set<String> targetedIps = runRequestsCapturingTargetedIps(config().setMaxRequestRetry(0).build());
        assertEquals(1, targetedIps.size(), "default mode should keep targeting and reusing a single connection");
    }

    @Test
    public void roundRobinFailsOverWhenSelectedIpIsDown() throws Exception {
        // server bound to 127.0.0.1 only, so 127.0.0.2 has no listener (refused on Linux / unreachable on macOS)
        Server localServer = new Server();
        ServerConnector connector = addHttpConnector(localServer);
        connector.setHost("127.0.0.1");
        localServer.setHandler(new EchoHandler());
        localServer.start();
        int localPort = connector.getLocalPort();
        try {
            Set<String> attemptedIps = ConcurrentHashMap.newKeySet();
            Set<String> connectedIps = ConcurrentHashMap.newKeySet();
            NameResolver<InetAddress> resolver = fixedResolver("127.0.0.2", "127.0.0.1");
            try (AsyncHttpClient client = asyncHttpClient(config().setLoadBalance(LoadBalance.ROUND_ROBIN).setMaxRequestRetry(0).build())) {
                for (int i = 0; i < 8; i++) {
                    Response response = client.executeRequest(
                            get("http://roundrobin.test:" + localPort + "/").setNameResolver(resolver),
                            new ConnectRecorder(attemptedIps, connectedIps)).get(TIMEOUT, SECONDS);
                    assertEquals(200, response.getStatusCode(), "request should succeed via failover even when 127.0.0.2 is selected");
                }
            }
            // the down IP was actually selected/attempted, and every successful connection landed on the reachable IP
            assertEquals(Set.of("127.0.0.1", "127.0.0.2"), attemptedIps);
            assertEquals(Set.of("127.0.0.1"), connectedIps);
        } finally {
            localServer.stop();
        }
    }

    @Test
    public void roundRobinRequestStillHitsRequestTimeout() throws Exception {
        // Regression guard: round-robin resolves up front WITHOUT scheduling the request timeout there; the
        // reuse-or-connect path must still schedule it exactly once. Point at a server that never responds
        // within the request timeout and assert the request times out (i.e. the new-connection round-robin
        // path did schedule the timeout).
        Server slow = new Server();
        ServerConnector connector = addHttpConnector(slow);
        slow.setHandler(new AbstractHandler() {
            @Override
            public void handle(String t, Request base, HttpServletRequest req, HttpServletResponse resp) {
                base.setHandled(true);
                try {
                    Thread.sleep(4000); // never responds within the 500ms request timeout below
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        slow.start();
        int slowPort = connector.getLocalPort();
        try {
            NameResolver<InetAddress> resolver = fixedResolver("127.0.0.1");
            try (AsyncHttpClient client = asyncHttpClient(config()
                    .setLoadBalance(LoadBalance.ROUND_ROBIN)
                    .setRequestTimeout(java.time.Duration.ofMillis(500))
                    .setMaxRequestRetry(0).build())) {
                ExecutionException thrown = assertThrows(ExecutionException.class, () ->
                        client.executeRequest(get("http://roundrobin.test:" + slowPort + "/").setNameResolver(resolver))
                                .get(TIMEOUT, SECONDS));
                assertInstanceOf(TimeoutException.class, thrown.getCause(),
                        "round-robin request must still hit the request timeout; got " + thrown.getCause());
            }
        } finally {
            slow.stop();
        }
    }

    @Test
    public void defaultModeDeprioritizesAFailedIpOnTheNextConnection() throws Exception {
        // The headline behavior this PR adds: the failed-IP cooldown now applies in DEFAULT mode too, so a
        // TCP-connect failure to an IP deprioritizes it (moves it to the back) on the next new connection.
        // Server bound to 127.0.0.1 only, so 127.0.0.2 has no listener (refused on Linux / unreachable on macOS).
        Server localServer = new Server();
        ServerConnector connector = addHttpConnector(localServer);
        connector.setHost("127.0.0.1");
        localServer.setHandler(new EchoHandler());
        localServer.start();
        int localPort = connector.getLocalPort();
        try {
            // Resolver hands back the dead IP first. keepAlive=false forces a fresh connection per request,
            // so every request re-orders and connects (a pooled reuse would never re-resolve). DEFAULT mode
            // (no setLoadBalance) — this is what previously always dialed the dead IP first.
            NameResolver<InetAddress> resolver = fixedResolver("127.0.0.2", "127.0.0.1");
            List<String> firstAttemptPerRequest = new ArrayList<>();
            try (AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(false).setMaxRequestRetry(0).build())) {
                for (int i = 0; i < 4; i++) {
                    List<String> attempts = new CopyOnWriteArrayList<>();
                    Response response = client.executeRequest(
                            get("http://cooldown.test:" + localPort + "/").setNameResolver(resolver),
                            new OrderedAttemptRecorder(attempts)).get(TIMEOUT, SECONDS);
                    assertEquals(200, response.getStatusCode(), "request should succeed via failover to the reachable IP");
                    firstAttemptPerRequest.add(attempts.get(0));
                }
            }
            // First request has nothing cooling yet, so it dials the dead IP first (resolver order) then fails over.
            assertEquals("127.0.0.2", firstAttemptPerRequest.get(0),
                    "the first connection follows resolver order because no failure has been recorded yet");
            // Once 127.0.0.2's connect failure is recorded, the cooldown moves it to the back, so every later
            // new connection dials the healthy 127.0.0.1 first — the point of extending the cooldown to DEFAULT mode.
            for (int i = 1; i < firstAttemptPerRequest.size(); i++) {
                assertEquals("127.0.0.1", firstAttemptPerRequest.get(i),
                        "DEFAULT mode must deprioritize the recently-failed IP on later new connections (request #" + i + ")");
            }
        } finally {
            localServer.stop();
        }
    }

    @Test
    public void roundRobinReResolvesAcrossSameHostPortChangingRedirect() throws Exception {
        // Regression test for the same-base redirect leak: round-robin caches the resolved addresses
        // (with the port baked in) and the IP-aware partition key. A same-host redirect that changes the
        // port (or scheme) must re-resolve instead of reusing the stale cached state. Before the fix the
        // host-only reuse guard kept the original-port addresses, so the redirected request dialed the
        // redirect server again and looped until max-redirects; now the base-aware guard re-resolves.
        Server targetServer = new Server();
        ServerConnector targetConnector = addHttpConnector(targetServer);
        targetServer.setHandler(new EchoHandler());
        targetServer.start();
        int targetPort = targetConnector.getLocalPort();

        Server redirectServer = new Server();
        ServerConnector redirectConnector = addHttpConnector(redirectServer);
        final String location = "http://roundrobin.test:" + targetPort + "/landing";
        redirectServer.setHandler(new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest req, HttpServletResponse resp) {
                resp.setStatus(HttpServletResponse.SC_FOUND); // 302, same host, different port
                resp.setHeader("Location", location);
                baseRequest.setHandled(true);
            }
        });
        redirectServer.start();
        int redirectPort = redirectConnector.getLocalPort();

        try {
            // Single resolved IP keeps the test deterministic across platforms; the bug affects single-IP
            // hosts too because the resolved addresses are always cached on the future.
            NameResolver<InetAddress> resolver = fixedResolver("127.0.0.1");
            try (AsyncHttpClient client = asyncHttpClient(
                    config().setLoadBalance(LoadBalance.ROUND_ROBIN).setFollowRedirect(true).setMaxRequestRetry(0).build())) {
                Response response = client.executeRequest(
                        get("http://roundrobin.test:" + redirectPort + "/").setNameResolver(resolver)).get(TIMEOUT, SECONDS);
                assertEquals(200, response.getStatusCode(),
                        "round-robin must re-resolve on a same-host port change so the redirect reaches the target port");
            }
        } finally {
            redirectServer.stop();
            targetServer.stop();
        }
    }
}
