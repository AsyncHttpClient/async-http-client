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

import io.netty.channel.Channel;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.NameResolver;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.asynchttpclient.test.EchoHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.test.TestUtils.TIMEOUT;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end coverage for {@link RequestSendType#ROUND_ROBIN}: a single host that resolves to several
 * loopback IPs (all served by one wildcard-bound test server) should have its requests spread across
 * every IP, whereas {@link RequestSendType#DEFAULT} keeps reusing a single pooled connection.
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
        Set<String> targetedIps = runRequestsCapturingTargetedIps(config().setRequestSendType(RequestSendType.ROUND_ROBIN).setMaxRequestRetry(0).build());
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
            try (AsyncHttpClient client = asyncHttpClient(config().setRequestSendType(RequestSendType.ROUND_ROBIN).setMaxRequestRetry(0).build())) {
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
}
