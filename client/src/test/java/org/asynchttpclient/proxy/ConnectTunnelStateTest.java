/*
 * Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.proxy;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

/**
 * A CONNECT is sent to the proxy over a socket that is still plaintext; the tunnel exists only once the
 * proxy has answered 2xx. AHC used to infer "the tunnel is up" from nothing more than "the last request was
 * a CONNECT", which is equally true when the proxy REJECTED it. On a 401 or a redirect the origin-request
 * interceptors then rebuilt the exchange as the ORIGIN request with {@code setReuseChannel(true)}, and
 * because that request is not a CONNECT the request factory attached the origin's {@code Authorization} to
 * it - handing the origin's credentials to the proxy, in the clear, on demand. A proxy needed only to
 * answer 401 (a non-preemptive realm, which is the default) or 302 to collect them.
 *
 * <p>The proxy here is a raw socket that records every request head it is sent, so the assertion is made on
 * the actual bytes on the wire rather than on any client-side state.
 */
public class ConnectTunnelStateTest {

  private static final String ORIGIN_USER = "origin-user";
  private static final String ORIGIN_PASSWORD = "origin-secret";
  // Base64("origin-user:origin-secret"), i.e. what a leak looks like on the wire.
  private static final String ORIGIN_BASIC = "b3JpZ2luLXVzZXI6b3JpZ2luLXNlY3JldA==";

  private RecordingProxy proxy;

  @AfterMethod(alwaysRun = true)
  public void tearDown() throws IOException {
    if (proxy != null) {
      proxy.close();
      proxy = null;
    }
  }

  /**
   * One request head as the proxy saw it, together with the identity of the TCP connection it arrived on.
   * The connection is the point: "the socket was closed" and "the socket was poisoned and left in the
   * pool" are indistinguishable from a single request, and only the second one leaks.
   */
  private static final class RecordedHead {

    final int connectionId;
    final String head;

    RecordedHead(int connectionId, String head) {
      this.connectionId = connectionId;
      this.head = head;
    }

    @Override
    public String toString() {
      return "[conn#" + connectionId + "] " + head;
    }
  }

  /**
   * A minimal HTTP proxy that answers each request head it receives with the next scripted response and
   * keeps the connection open, recording everything the client sends on it afterwards. Each accepted
   * connection is served on its own thread and numbered, so a client that opens a second connection is
   * told apart from one that reuses the first.
   */
  private static final class RecordingProxy implements Closeable {

    private final ServerSocket serverSocket;
    private final String[] responses;
    private final List<RecordedHead> requestHeads = new CopyOnWriteArrayList<>();
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private final AtomicInteger connectionIds = new AtomicInteger();
    /**
     * Counts down to the number of request heads the UNFIXED client would send, so a run that reproduces
     * the leak does not have to wait out a request timeout to record it.
     */
    private final CountDownLatch expectedHeads;
    private final Thread thread;

    RecordingProxy(int expectedHeads, String... responses) throws IOException {
      this.responses = responses;
      this.expectedHeads = new CountDownLatch(expectedHeads);
      serverSocket = new ServerSocket(0, 4, InetAddress.getByName("localhost"));
      thread = new Thread(this::serve);
      thread.setDaemon(true);
      thread.start();
    }

    int port() {
      return serverSocket.getLocalPort();
    }

    private void serve() {
      while (!serverSocket.isClosed()) {
        try {
          Socket socket = serverSocket.accept();
          accepted.add(socket);
          int connectionId = connectionIds.incrementAndGet();
          Thread connectionThread = new Thread(() -> serveConnection(socket, connectionId));
          connectionThread.setDaemon(true);
          connectionThread.start();
        } catch (Exception ignored) {
          // accept() throws once the server socket is closed in tearDown.
          return;
        }
      }
    }

    private void serveConnection(Socket socket, int connectionId) {
      try (Socket toClose = socket) {
        toClose.setSoTimeout(5000);
        InputStream in = toClose.getInputStream();
        OutputStream out = toClose.getOutputStream();
        int answered = 0;
        String head;
        while ((head = readHead(in)) != null) {
          requestHeads.add(new RecordedHead(connectionId, head));
          expectedHeads.countDown();
          if (answered < responses.length) {
            out.write(responses[answered++].getBytes(StandardCharsets.US_ASCII));
            out.flush();
          }
        }
      } catch (Exception ignored) {
        // A read times out or fails once the client has nothing more to say on this connection. Either
        // way there is nothing left to record on it.
      }
    }

    /**
     * Reads one request head, i.e. up to and including the CRLFCRLF. Returns null at end of stream. Note
     * that a TLS ClientHello (which is what a client sends once a tunnel really is established) contains no
     * CRLFCRLF, so it is never mistaken for a request.
     */
    private static String readHead(InputStream in) throws IOException {
      StringBuilder head = new StringBuilder();
      int b3 = -1, b2 = -1, b1 = -1, b;
      while ((b = in.read()) != -1) {
        head.append((char) b);
        if (b3 == '\r' && b2 == '\n' && b1 == '\r' && b == '\n') {
          return head.toString();
        }
        b3 = b2;
        b2 = b1;
        b1 = b;
      }
      return null;
    }

    void awaitExpectedHeads() throws InterruptedException {
      expectedHeads.await(6, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws IOException {
      serverSocket.close();
      for (Socket socket : accepted) {
        try {
          socket.close();
        } catch (IOException ignored) {
          // Already closed by the client or by the connection thread.
        }
      }
      thread.interrupt();
    }
  }

  private static boolean isConnect(String head) {
    return head.startsWith("CONNECT ");
  }

  /**
   * The origin's Authorization, as opposed to Proxy-Authorization which legitimately goes to the proxy.
   */
  private static boolean carriesOriginAuthorization(String head) {
    for (String line : head.split("\r\n")) {
      String lower = line.toLowerCase();
      if (lower.startsWith("authorization:") && line.contains(ORIGIN_BASIC)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Any Authorization at all, whatever it carries. Nothing addressed to the origin has any business on a
   * socket the proxy refused to turn into a tunnel.
   */
  private static boolean carriesAnyAuthorization(String head) {
    for (String line : head.split("\r\n")) {
      if (line.toLowerCase().startsWith("authorization:")) {
        return true;
      }
    }
    return false;
  }

  /**
   * The contract for a proxy that never established a tunnel: the only thing it may ever be sent is a
   * CONNECT, and certainly not the origin's credentials.
   */
  private void assertOnlyConnectsReachedTheProxy() {
    assertFalse(proxy.requestHeads.isEmpty(), "the proxy should have received the CONNECT");
    // The credential leak is checked across every head before the weaker "it was not even a CONNECT"
    // check, so that a failure names the secret that escaped rather than the first symptom on the wire.
    for (RecordedHead recorded : proxy.requestHeads) {
      assertFalse(carriesAnyAuthorization(recorded.head),
              "the origin's credentials must never be written to a socket on which no tunnel was "
                      + "established; the proxy received:\n" + recorded);
    }
    for (RecordedHead recorded : proxy.requestHeads) {
      assertTrue(isConnect(recorded.head),
              "a proxy that has not established a tunnel must only ever be sent CONNECTs, but got:\n" + recorded);
    }
  }

  private AsyncHttpClient clientWithOriginRealm(boolean followRedirect) {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(followRedirect)
            .setRequestTimeout(4000)
            // Non-preemptive, which is the default: the credentials are sent only in answer to a
            // challenge. That is precisely what makes a 401 from the proxy enough to solicit them.
            .setRealm(basicAuthRealm(ORIGIN_USER, ORIGIN_PASSWORD).build())
            .setProxyServer(proxyServer("localhost", proxy.port()).build())
            .build();
    return new DefaultAsyncHttpClient(config);
  }

  /**
   * The proxy rejects the CONNECT with a 401. The tunnel does not exist, so nothing that follows may carry
   * the origin's credentials down that socket.
   */
  @Test(timeOut = 30000)
  public void proxyRejectingConnectWith401DoesNotHarvestOriginCredentials() throws Exception {
    // Two heads are scripted because an UNFIXED client sends a second request - the origin GET, bearing
    // the credentials the 401 solicited - and answering it keeps the reproduction fast.
    proxy = new RecordingProxy(2,
            "HTTP/1.1 401 Unauthorized\r\n"
                    + "WWW-Authenticate: Basic realm=\"origin\"\r\n"
                    + "Content-Length: 0\r\n"
                    + "\r\n",
            "HTTP/1.1 200 OK\r\n"
                    + "Content-Length: 0\r\n"
                    + "\r\n");

    Response response = null;
    try (AsyncHttpClient client = clientWithOriginRealm(false)) {
      try {
        response = client.prepareGet("https://origin.example.com/secret").execute().get(10, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        // What was written is the contract; whether the exchange completed is asserted separately below.
      }
    } finally {
      proxy.awaitExpectedHeads();
    }

    assertOnlyConnectsReachedTheProxy();
    // The 401 is the proxy's answer to the CONNECT. It is surfaced to the caller as-is, and must never be
    // mistaken for an origin challenge to answer.
    assertEquals(response == null ? null : response.getStatusCode(), (Integer) 401,
            "the proxy's rejection of the CONNECT must be surfaced to the caller");
  }

  /**
   * Same, with a redirect instead of a challenge. The Location deliberately stays on the same origin, so
   * the redirect is not cross-origin and the credential-stripping path does not mask the bug.
   */
  @Test(timeOut = 30000)
  public void proxyRejectingConnectWith302DoesNotHarvestOriginCredentials() throws Exception {
    // An unfixed client follows the redirect on the same plaintext socket, is challenged there, and sends
    // the credentials on a third request.
    proxy = new RecordingProxy(3,
            "HTTP/1.1 302 Found\r\n"
                    + "Location: https://origin.example.com/secret2\r\n"
                    + "Content-Length: 0\r\n"
                    + "\r\n",
            "HTTP/1.1 401 Unauthorized\r\n"
                    + "WWW-Authenticate: Basic realm=\"origin\"\r\n"
                    + "Content-Length: 0\r\n"
                    + "\r\n",
            "HTTP/1.1 200 OK\r\n"
                    + "Content-Length: 0\r\n"
                    + "\r\n");

    try (AsyncHttpClient client = clientWithOriginRealm(true)) {
      try {
        client.prepareGet("https://origin.example.com/secret").execute().get(10, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        // Whether the exchange completes is not the contract under test; what was written is.
      }
    } finally {
      proxy.awaitExpectedHeads();
    }

    assertOnlyConnectsReachedTheProxy();
  }

  /**
   * The regression guard for the other direction: once the proxy DOES establish the tunnel, the exchange
   * must proceed exactly as before - the CONNECT carries Proxy-Authorization and the tunnelled request
   * carries the origin's Authorization. A {@code ws://} target is used so the tunnelled hop is cleartext
   * and can be inspected; the {@code https://} equivalent is covered end to end by HttpsProxyTest.
   */
  @Test(timeOut = 30000)
  public void establishedTunnelStillCarriesOriginAuthOnTheTunnelledRequest() throws Exception {
    proxy = new RecordingProxy(2, "HTTP/1.1 200 Connection Established\r\n\r\n");

    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setRequestTimeout(4000)
            .setRealm(basicAuthRealm(ORIGIN_USER, ORIGIN_PASSWORD).setUsePreemptiveAuth(true).build())
            .setProxyServer(proxyServer("localhost", proxy.port())
                    .setRealm(basicAuthRealm("proxy-user", "proxy-secret").setUsePreemptiveAuth(true))
                    .build())
            .build();

    try (AsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      try {
        client.prepareGet("ws://origin.example.com/socket")
                .execute(new WebSocketUpgradeHandler.Builder().build())
                .get(10, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        // The fake proxy never completes the WebSocket handshake; only what was written matters here.
      }
    } finally {
      proxy.awaitExpectedHeads();
    }

    assertTrue(proxy.requestHeads.size() >= 2,
            "the tunnelled request must be sent on the same socket after the 200, but got: " + proxy.requestHeads);

    RecordedHead connect = proxy.requestHeads.get(0);
    assertTrue(isConnect(connect.head), "the first request must be a CONNECT: " + connect);
    assertTrue(connect.head.toLowerCase().contains("proxy-authorization: basic "),
            "the CONNECT is addressed to the proxy and must carry its credentials:\n" + connect);
    assertFalse(carriesOriginAuthorization(connect.head),
            "the CONNECT travels in the clear and must NOT carry the origin's credentials:\n" + connect);

    RecordedHead tunnelled = proxy.requestHeads.get(1);
    assertFalse(isConnect(tunnelled.head), "the second request must be the tunnelled one: " + tunnelled);
    assertEquals(tunnelled.connectionId, connect.connectionId,
            "the tunnelled request must go down the very socket the CONNECT established: " + proxy.requestHeads);
    assertTrue(carriesOriginAuthorization(tunnelled.head),
            "once the tunnel is established the origin's credentials must be sent through it:\n" + tunnelled);
  }

  /**
   * Two requests, one client. Asserting on a single request is not enough: "the socket was closed" and
   * "the socket was left in the pool, still plaintext, under the https-origin partition key" produce the
   * same one request head, and only the second one leaks. It leaks on the request AFTER the rejected
   * CONNECT, because {@code NettyRequestSender.sendRequestThroughProxy} takes a channel polled under that
   * key to be a tunnel that is already up and therefore sends the ORIGIN request on it rather than a new
   * CONNECT - at which point the proxy's next 401 collects the origin's credentials.
   *
   * <p>So the second request must arrive on a NEW connection, identified server-side, and no Authorization
   * may appear anywhere on the wire.
   */
  @Test(timeOut = 30000)
  public void proxyRejectingConnectDoesNotLeavePoisonedConnectionInThePool() throws Exception {
    String unauthorized = "HTTP/1.1 401 Unauthorized\r\n"
            + "WWW-Authenticate: Basic realm=\"origin\"\r\n"
            + "Content-Length: 0\r\n"
            + "\r\n";
    // Three heads are scripted because that is what the UNFIXED client sends: the CONNECT, then the origin
    // GET down the pooled plaintext socket, then the same GET carrying the credentials the second 401
    // solicited. Answering all three keeps the reproduction fast and lets the leak be recorded in full.
    proxy = new RecordingProxy(3, unauthorized, unauthorized,
            "HTTP/1.1 200 OK\r\n"
                    + "Content-Length: 0\r\n"
                    + "\r\n");

    try (AsyncHttpClient client = clientWithOriginRealm(false)) {
      for (int i = 0; i < 2; i++) {
        try {
          client.prepareGet("https://origin.example.com/secret").execute().get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
          // What was written is the contract; the per-request status is asserted by the tests above.
        }
      }
    } finally {
      // Give a client that would still leak every chance to send the third head before asserting.
      proxy.awaitExpectedHeads();
    }

    assertOnlyConnectsReachedTheProxy();

    assertTrue(proxy.requestHeads.size() >= 2,
            "the second request must have reached the proxy, but it only saw: " + proxy.requestHeads);
    RecordedHead first = proxy.requestHeads.get(0);
    for (int i = 1; i < proxy.requestHeads.size(); i++) {
      assertNotEquals(proxy.requestHeads.get(i).connectionId, first.connectionId,
              "the socket on which the proxy REFUSED the CONNECT is not a tunnel and must never be pooled; "
                      + "the client kept using it: " + proxy.requestHeads);
    }
  }
}
