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
package org.asynchttpclient.ws;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.RedirectPolicy;
import org.asynchttpclient.handler.RedirectRefusedException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code wss} handshake answered with a redirect to {@code ws} is a cleartext downgrade, and it is followed
 * today: {@code Uri.isSecured()} is true for {@code wss}, WebSocket upgrades reach
 * {@code Redirect30xInterceptor}, and {@code validateWebSocketRequest} accepts the hop.
 * <p>
 * The fixture annotates BOTH halves deliberately. {@link AbstractBasicWebSocketTest#setUpGlobal()} is
 * {@code @BeforeEach} while its {@code tearDownGlobal()} override carries no annotation at all, so inheriting
 * the pair starts a server per test and stops none. Nor is a differently-named fixture method declared here -
 * {@code ws/RedirectTest} does that, with the result that both its own and the base class's method run and two
 * servers are created per test.
 */
public class WebSocketRedirectPolicyTest extends AbstractBasicWebSocketTest {

    @Override
    @BeforeEach
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector plain = addHttpConnector(server);
        ServerConnector secure = addHttpsConnector(server);

        HandlerList handlers = new HandlerList();
        handlers.addHandler(new AbstractHandler() {
            @Override
            public void handle(String path, Request request, HttpServletRequest servletRequest,
                               HttpServletResponse response) throws IOException {
                // Only the TLS connector redirects; the plaintext one serves the WebSocket endpoint.
                if (request.getLocalPort() == port2) {
                    response.setStatus(302);
                    response.setHeader("Location", "ws://localhost:" + port1 + "/");
                    response.getOutputStream().flush();
                    request.setHandled(true);
                }
            }
        });
        handlers.addHandler(configureHandler());
        server.setHandler(handlers);

        server.start();
        port1 = plain.getLocalPort();
        port2 = secure.getLocalPort();
        logger.info("Local ws/wss server started: plain={} secure={}", port1, port2);
    }

    @Override
    @AfterEach
    public void tearDownGlobal() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    private String secureHandshakeUrl() {
        return "wss://localhost:" + port2 + "/";
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void wssToWsDowngradeIsRefused() throws Exception {
        AtomicBoolean opened = new AtomicBoolean();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch errored = new CountDownLatch(1);

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setFollowRedirect(true)
                .setUseInsecureTrustManager(true)
                .setRedirectPolicy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {

            WebSocketUpgradeHandler handler = new WebSocketUpgradeHandler.Builder()
                    .addWebSocketListener(new WebSocketListener() {
                        @Override
                        public void onOpen(WebSocket websocket) {
                            opened.set(true);
                        }

                        @Override
                        public void onClose(WebSocket websocket, int code, String reason) {
                        }

                        @Override
                        public void onError(Throwable t) {
                            thrown.set(t);
                            errored.countDown();
                        }
                    }).build();

            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> client.prepareGet(secureHandshakeUrl()).execute(handler).get(30, TimeUnit.SECONDS),
                    "a refused wss handshake must fail the future, not complete it with a null WebSocket");
            assertInstanceOf(RedirectRefusedException.class, e.getCause(),
                    "cause was " + e.getCause());

            assertTrue(errored.await(5, TimeUnit.SECONDS), "onError must fire");
            assertInstanceOf(RedirectRefusedException.class, thrown.get());
            assertFalse(opened.get(), "the cleartext WebSocket must never open");
        }
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void wssToWsDowngradeIsFollowedUnderAllowAll() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setFollowRedirect(true)
                .setUseInsecureTrustManager(true)
                .setRedirectPolicy(RedirectPolicy.ALLOW_ALL))) {

            WebSocketUpgradeHandler handler = new WebSocketUpgradeHandler.Builder()
                    .addWebSocketListener(new WebSocketListener() {
                        @Override
                        public void onOpen(WebSocket websocket) {
                            opened.countDown();
                        }

                        @Override
                        public void onClose(WebSocket websocket, int code, String reason) {
                        }

                        @Override
                        public void onError(Throwable t) {
                        }
                    }).build();

            WebSocket websocket = client.prepareGet(secureHandshakeUrl()).execute(handler).get(30, TimeUnit.SECONDS);
            assertTrue(opened.await(10, TimeUnit.SECONDS), "ALLOW_ALL must keep following the wss to ws hop");
            websocket.sendCloseFrame();
        }
    }
}
