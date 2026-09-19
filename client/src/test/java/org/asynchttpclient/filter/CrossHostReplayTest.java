/*
 * Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.filter;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.RedirectPolicy;
import org.asynchttpclient.Realm;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.proxy.ProxyServer;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link ResponseFilter} that replays onto a <em>different</em> host is the documented way to do
 * failover. The request the future was built for and the request it ends up sending then disagree, and
 * anything derived from the target has to move with it.
 * <p>
 * The existing replay tests all replay to the same host, so nothing here was covered: the future kept
 * describing the first origin, which sent that origin's credentials to the second one and filed the second
 * one's socket in the connection pool under the first one's key.
 */
public class CrossHostReplayTest {

    private static final String SECRET = "s3cr3t-for-A";

    /**
     * A tiny origin that records the request line and Authorization header of everything it receives.
     */
    private static final class Recorder implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0);
        final List<String> seen = new CopyOnWriteArrayList<>();
        private final int status;

        Recorder(int status) throws IOException {
            this.status = status;
            Thread t = new Thread(this::serve);
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void serve() {
            while (!socket.isClosed()) {
                final Socket s;
                try {
                    s = socket.accept();
                } catch (IOException e) {
                    return;
                }
                // One thread per connection, and the connection is kept open across requests. Without
                // keep-alive nothing is ever offered to the connection pool, and a test about pool keys
                // would pass no matter what the product code did.
                Thread worker = new Thread(() -> handle(s));
                worker.setDaemon(true);
                worker.start();
            }
        }

        private void handle(Socket s) {
            try (Socket conn = s) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), ISO_8859_1));
                while (true) {
                    String requestLine = in.readLine();
                    if (requestLine == null) {
                        return;
                    }
                    String auth = null;
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("authorization:")) {
                            auth = line.substring("authorization:".length()).trim();
                        }
                    }
                    seen.add(requestLine.split(" ")[1] + " auth=" + (auth == null ? "<none>" : auth));
                    conn.getOutputStream().write(
                            ("HTTP/1.1 " + status + " X\r\nContent-Length: 0\r\n\r\n").getBytes(ISO_8859_1));
                    conn.getOutputStream().flush();
                }
            } catch (IOException ignored) {
                // client hung up
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * Replays onto {@code url} when the origin answers 503. ResponseFilter.filter is generic, so this
     * cannot be a lambda.
     */
    private static ResponseFilter failoverTo(String url) {
        return new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (ctx.getResponseStatus() != null && ctx.getResponseStatus().getStatusCode() == 503) {
                    return new FilterContext.FilterContextBuilder<>(ctx)
                            .request(new RequestBuilder("GET").setUrl(url).build())
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };
    }

    /**
     * Replays onto {@code url} through the proxy on {@code proxyPort} when the origin answers 503.
     */
    private static ResponseFilter failoverVia(String url, int proxyPort) {
        return new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (ctx.getResponseStatus() != null && ctx.getResponseStatus().getStatusCode() == 503) {
                    return new FilterContext.FilterContextBuilder<>(ctx)
                            .request(new RequestBuilder("GET").setUrl(url)
                                    .setProxyServer(new ProxyServer.Builder("127.0.0.1", proxyPort).build())
                                    .build())
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };
    }

    private static Realm realmForA() {
        return Dsl.basicAuthRealm("alice", SECRET).setUsePreemptiveAuth(true).build();
    }

    /**
     * The credentials configured for the first origin must not be sent to the host the request is replayed
     * onto. The replay carries no realm of its own, and the previous origin's must not be reused for it.
     */
    @Test
    public void replayToADifferentHostDoesNotCarryTheFirstHostsCredentials() throws Exception {
        try (Recorder a = new Recorder(503); Recorder b = new Recorder(200)) {
            String urlB = "http://127.0.0.1:" + b.port() + "/failover";
            ResponseFilter failover = failoverTo(urlB);

            try (AsyncHttpClient client = Dsl.asyncHttpClient(
                    Dsl.config().setMaxRequestRetry(0).addResponseFilter(failover))) {
                client.prepareGet("http://127.0.0.1:" + a.port() + "/probe")
                        .setRealm(realmForA())
                        .execute().get(30, TimeUnit.SECONDS);
            }

            assertEquals(1, b.seen.size(), "the failover host should have been called exactly once: " + b.seen);
            assertTrue(b.seen.get(0).endsWith("auth=<none>"),
                    "the first host's credentials must not follow the replay: " + b.seen.get(0));
        }
    }

    /**
     * The socket opened for the replay target must be filed in the connection pool under that target, not
     * under the origin the future was created for. Filed under the wrong key, a later request the
     * application addresses to the first host is served over the connection to the second one, and the
     * first host's credentials go there with it.
     */
    @Test
    public void replayToADifferentHostDoesNotFileTheSocketUnderTheFirstHostsKey() throws Exception {
        try (Recorder a = new Recorder(503); Recorder b = new Recorder(200)) {
            String urlB = "http://127.0.0.1:" + b.port() + "/failover";
            ResponseFilter failover = failoverTo(urlB);

            try (AsyncHttpClient client = Dsl.asyncHttpClient(
                    Dsl.config().setMaxRequestRetry(0).addResponseFilter(failover))) {
                client.prepareGet("http://127.0.0.1:" + a.port() + "/probe")
                        .setRealm(realmForA())
                        .execute().get(30, TimeUnit.SECONDS);

                // Addressed to A. If the replay left B's socket pooled under A's key, this is served by B.
                client.prepareGet("http://127.0.0.1:" + a.port() + "/admin")
                        .setRealm(realmForA())
                        .execute().get(30, TimeUnit.SECONDS);
            }

            assertTrue(b.seen.stream().noneMatch(r -> r.startsWith("/admin")),
                    "a request addressed to the first host was served by the failover host: " + b.seen);
            assertTrue(b.seen.stream().noneMatch(r -> r.contains(SECRET) || r.contains("YWxpY2U6")),
                    "the first host's credentials reached the failover host: " + b.seen);
        }
    }

    /**
     * A replay that routes through a proxy must file its socket under a key naming that proxy. The proxy is
     * half of the connection pool key and is memoized alongside the target, so moving the target while the
     * proxy still names the previous route files a proxied connection under the direct key for the new
     * host. The next request the application sends directly to that host then draws a socket that runs
     * through the proxy, and its credentials go to the proxy.
     */
    @Test
    public void replayThroughAProxyDoesNotFileTheProxiedSocketAsDirect() throws Exception {
        try (Recorder a = new Recorder(503); Recorder proxy = new Recorder(200); Recorder b = new Recorder(200)) {
            String urlB = "http://127.0.0.1:" + b.port() + "/failover";

            try (AsyncHttpClient client = Dsl.asyncHttpClient(
                    Dsl.config().setMaxRequestRetry(0).addResponseFilter(failoverVia(urlB, proxy.port())))) {
                // Direct to A, which fails over onto B through the proxy.
                client.prepareGet("http://127.0.0.1:" + a.port() + "/probe")
                        .setRealm(realmForA())
                        .execute().get(30, TimeUnit.SECONDS);

                // Addressed directly to B, no proxy. Must not reuse the connection that runs through one.
                client.prepareGet("http://127.0.0.1:" + b.port() + "/admin")
                        .setRealm(realmForA())
                        .execute().get(30, TimeUnit.SECONDS);
            }

            assertTrue(proxy.seen.stream().noneMatch(r -> r.contains("/admin")),
                    "a request addressed directly to the origin was served over the pooled proxy connection: "
                            + proxy.seen);
        }
    }

    /**
     * A filter replay is deliberately not governed by {@code RedirectPolicy}: the difference is who picked the
     * target. A redirect target comes from the server, which is the policy's whole premise; a replay target
     * comes from the application's own code. Without this test the next auditor reads that as a bypass, or
     * "fixes" it and breaks the failover this class documents. Credential stripping on replay is unaffected.
     */
    @Test
    public void crossHostReplayIsNotGovernedByRedirectPolicy() throws Exception {
        try (Recorder a = new Recorder(503); Recorder b = new Recorder(200)) {
            String urlB = "http://localhost:" + b.port() + "/on-b";
            try (AsyncHttpClient client = Dsl.asyncHttpClient(Dsl.config()
                    .setMaxRequestRetry(0)
                    .setFollowRedirect(true)
                    // If replays were governed, the strictest policy would refuse this one.
                    .setRedirectPolicy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY)
                    .addResponseFilter(failoverTo(urlB)))) {

                Response response = client.prepareGet("http://localhost:" + a.port() + "/on-a")
                        .execute().get(10, TimeUnit.SECONDS);

                assertEquals(200, response.getStatusCode(), "the application's own failover must still work");
                assertTrue(b.seen.stream().anyMatch(line -> line.startsWith("/on-b")),
                        "the replay target must have been reached, got " + b.seen);
            }
        }
    }
}
