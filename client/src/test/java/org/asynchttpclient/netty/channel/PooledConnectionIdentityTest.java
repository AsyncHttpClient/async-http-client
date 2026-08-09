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
package org.asynchttpclient.netty.channel;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.Realm;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.testng.Assert.assertEquals;

/**
 * A connection authenticated by NTLM or Negotiate belongs to the identity that authenticated it, because
 * those schemes authenticate the socket rather than the request. Handing it to another principal makes the
 * server serve that request as the first principal, with nothing on the wire to show it.
 * <p>
 * Counting distinct accepted sockets is what makes this observable: reuse and isolation are invisible in
 * the responses, and the offer and poll sides deriving their key differently shows up only here.
 */
public class PooledConnectionIdentityTest {

    /** Keep-alive origin that records how many distinct connections it accepted. */
    private static final class CountingServer implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0);
        final Set<Integer> connections = ConcurrentHashMap.newKeySet();

        CountingServer() throws IOException {
            Thread t = new Thread(this::accept);
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void accept() {
            while (!socket.isClosed()) {
                final Socket s;
                try {
                    s = socket.accept();
                } catch (IOException e) {
                    return;
                }
                connections.add(s.getPort());
                Thread w = new Thread(() -> serve(s));
                w.setDaemon(true);
                w.start();
            }
        }

        private void serve(Socket s) {
            try (Socket conn = s) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), ISO_8859_1));
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isEmpty()) {
                        conn.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(ISO_8859_1));
                        conn.getOutputStream().flush();
                    }
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

    private static Realm ntlm(String principal) {
        return new Realm.Builder(principal, "secret")
                .setScheme(Realm.AuthScheme.NTLM)
                .setUsePreemptiveAuth(true)
                .setNtlmDomain("DOMAIN")
                .setNtlmHost("host")
                .build();
    }

    @Test
    public void twoPrincipalsDoNotShareAPooledConnection() throws Exception {
        try (CountingServer server = new CountingServer();
             AsyncHttpClient client = Dsl.asyncHttpClient(Dsl.config().setMaxRequestRetry(0))) {
            String url = "http://127.0.0.1:" + server.port() + "/";

            client.prepareGet(url).setRealm(ntlm("alice")).execute().get(30, TimeUnit.SECONDS);
            client.prepareGet(url).setRealm(ntlm("bob")).execute().get(30, TimeUnit.SECONDS);

            assertEquals(server.connections.size(), 2,
                    "bob must not be served over the socket alice authenticated");
        }
    }

    /**
     * The isolation must not cost every other request its connection reuse, and one identity must keep
     * reusing its own.
     */
    @Test
    public void oneIdentityStillReusesItsOwnConnection() throws Exception {
        try (CountingServer server = new CountingServer();
             AsyncHttpClient client = Dsl.asyncHttpClient(Dsl.config().setMaxRequestRetry(0))) {
            String url = "http://127.0.0.1:" + server.port() + "/";

            client.prepareGet(url).setRealm(ntlm("alice")).execute().get(30, TimeUnit.SECONDS);
            client.prepareGet(url).setRealm(ntlm("alice")).execute().get(30, TimeUnit.SECONDS);

            assertEquals(server.connections.size(), 1,
                    "alice's second request must reuse her own pooled connection");
        }
    }

    @Test
    public void requestsWithoutARealmStillReuseAConnection() throws Exception {
        try (CountingServer server = new CountingServer();
             AsyncHttpClient client = Dsl.asyncHttpClient(Dsl.config().setMaxRequestRetry(0))) {
            String url = "http://127.0.0.1:" + server.port() + "/";

            client.prepareGet(url).execute().get(30, TimeUnit.SECONDS);
            client.prepareGet(url).execute().get(30, TimeUnit.SECONDS);

            assertEquals(server.connections.size(), 1, "ordinary pooling must be unaffected");
        }
    }
}
