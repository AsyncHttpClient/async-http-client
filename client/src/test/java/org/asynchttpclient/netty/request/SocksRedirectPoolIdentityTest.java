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
package org.asynchttpclient.netty.request;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Response;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A cross-origin redirect clears the future's proxy realm, but the SOCKS handshake still logs in with the
 * proxy's own. The socket opened for the redirect target must be pooled under that login.
 */
class SocksRedirectPoolIdentityTest {

    private ServerSocket server;
    private final List<String> logins = new CopyOnWriteArrayList<>();
    private Thread acceptor;
    private int redirectPort;
    private int targetPort;

    @BeforeEach
    void start() throws IOException {
        server = new ServerSocket(0);
        // Ports only name the origin inside the SOCKS CONNECT; nothing listens on them.
        redirectPort = 41111;
        targetPort = 42222;
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    Thread worker = new Thread(() -> serve(s));
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
    }

    private void serve(Socket s) {
        try (Socket socket = s) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            in.readUnsignedByte();
            int n = in.readUnsignedByte();
            byte[] methods = new byte[n];
            in.readFully(methods);
            boolean password = false;
            for (byte m : methods) {
                password |= m == 2;
            }
            String user = "<anonymous>";
            if (password) {
                out.write(new byte[]{5, 2});
                in.readUnsignedByte();
                byte[] u = new byte[in.readUnsignedByte()];
                in.readFully(u);
                byte[] p = new byte[in.readUnsignedByte()];
                in.readFully(p);
                user = new String(u, StandardCharsets.US_ASCII);
                out.write(new byte[]{1, 0});
            } else {
                out.write(new byte[]{5, 0});
            }
            in.readUnsignedByte();
            in.readUnsignedByte();
            in.readUnsignedByte();
            int atyp = in.readUnsignedByte();
            if (atyp == 1) {
                in.readFully(new byte[4]);
            } else if (atyp == 3) {
                in.readFully(new byte[in.readUnsignedByte()]);
            } else {
                in.readFully(new byte[16]);
            }
            int port = in.readUnsignedShort();
            out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            logins.add(user + "->" + port);
            while (true) {
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int state = 0;
                while (state < 4) {
                    int b = in.read();
                    if (b < 0) {
                        return;
                    }
                    head.write(b);
                    state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
                }
                String resp;
                if (port == redirectPort) {
                    resp = "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + targetPort + "/\r\nContent-Length: 0\r\n\r\n";
                } else {
                    resp = "HTTP/1.1 200 OK\r\nContent-Length: " + user.length() + "\r\n\r\n" + user;
                }
                out.write(resp.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private ProxyServer socks(String user) {
        ProxyServer.Builder b = new ProxyServer.Builder("127.0.0.1", server.getLocalPort()).setProxyType(ProxyType.SOCKS_V5);
        if (user != null) {
            b.setRealm(new Realm.Builder(user, "pw-" + user).setScheme(Realm.AuthScheme.BASIC).build());
        }
        return b.build();
    }

    @Test
    void aRedirectedSocksConnectionKeepsItsLogin() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true).setKeepAlive(true))) {
            Response control = client.prepareGet("http://127.0.0.1:" + targetPort + "/").setProxyServer(socks("bob"))
                    .execute().get(10, TimeUnit.SECONDS);
            assertEquals("bob", control.getResponseBody(), "control: a direct request uses its own login");

            Response alice = client.prepareGet("http://127.0.0.1:" + redirectPort + "/").setProxyServer(socks("alice"))
                    .execute().get(10, TimeUnit.SECONDS);
            assertEquals("alice", alice.getResponseBody());

            Response anon = client.prepareGet("http://127.0.0.1:" + targetPort + "/").setProxyServer(socks(null))
                    .execute().get(10, TimeUnit.SECONDS);
            Response carol = client.prepareGet("http://127.0.0.1:" + redirectPort + "/").setProxyServer(socks("carol"))
                    .execute().get(10, TimeUnit.SECONDS);
            assertEquals("<anonymous>", anon.getResponseBody(), "logins seen: " + logins);
            assertEquals("carol", carol.getResponseBody(), "logins seen: " + logins);
        }
    }
}
