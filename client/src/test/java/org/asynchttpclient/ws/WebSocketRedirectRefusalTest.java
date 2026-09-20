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
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code wss} to {@code ws} is a cleartext downgrade, and RFC 6455 section 4.1 leaves a client free not to
 * follow it. The WebSocket path reaches the same interceptor as HTTP.
 * <p>
 * Both fixture methods are re-annotated because the base class drops the annotation when it overrides
 * {@code tearDownGlobal}, so declaring only half the pair starts a server per test and stops none.
 */
public class WebSocketRedirectRefusalTest extends AbstractBasicWebSocketTest {

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
                if (request.getLocalPort() == port2) {
                    response.sendRedirect("ws://localhost:" + port1 + "/");
                }
            }
        });
        handlers.addHandler(configureHandler());
        server.setHandler(handlers);

        server.start();
        port1 = plain.getLocalPort();
        port2 = secure.getLocalPort();
    }

    @Override
    @AfterEach
    public void tearDownGlobal() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void downgradeToCleartextIsRefused() throws Exception {
        Outcome outcome = connect(config()
                .setFollowRedirect(true)
                .setUseInsecureTrustManager(true)
                .setRefuseSchemeDowngradeOnRedirect(true));

        assertFalse(outcome.opened.get(), "the cleartext WebSocket must never open");
        assertInstanceOf(RedirectRefusedException.class, outcome.failure.get());
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void downgradeToCleartextIsFollowedByDefault() throws Exception {
        Outcome outcome = connect(config()
                .setFollowRedirect(true)
                .setUseInsecureTrustManager(true));

        assertTrue(outcome.opened.get(), "the default posture still follows the hop");
    }

    private Outcome connect(DefaultAsyncHttpClientConfig.Builder builder) throws Exception {
        Outcome outcome = new Outcome();
        try (AsyncHttpClient client = asyncHttpClient(builder)) {
            client.prepareGet("wss://localhost:" + port2 + "/")
                    .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

                        @Override
                        public void onOpen(WebSocket websocket) {
                            outcome.opened.set(true);
                            outcome.done.countDown();
                        }

                        @Override
                        public void onClose(WebSocket websocket, int code, String reason) {
                        }

                        @Override
                        public void onError(Throwable t) {
                            outcome.failure.set(t);
                            outcome.done.countDown();
                        }
                    }).build());

            assertTrue(outcome.done.await(30, TimeUnit.SECONDS), "neither onOpen nor onError fired");
        }
        return outcome;
    }

    private static class Outcome {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean opened = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
    }
}
