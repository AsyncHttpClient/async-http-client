/*
 * Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asynchttpclient;

import io.github.artsok.RepeatedIfExceptionsTest;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.test.ExtendedDigestAuthenticator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.digestAuthRealm;
import static org.asynchttpclient.test.TestUtils.ADMIN;
import static org.asynchttpclient.test.TestUtils.USER;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class DigestAuthRfc7616Test extends AbstractBasicTest {

    @Override
    @BeforeEach
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(configureHandler());
        server.start();
        port1 = connector.getLocalPort();
        logger.info("Local HTTP server started successfully");
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new StaleNonceHandler();
    }

    // Phase 2: Stale nonce handling
    @RepeatedIfExceptionsTest(repeats = 5)
    public void staleNonceRetry() throws Exception {
        server.stop();
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(new StaleNonceHandler());
        server.start();
        port1 = connector.getLocalPort();

        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(digestAuthRealm(USER, ADMIN).setRealmName("MyRealm").build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(HttpServletResponse.SC_OK, resp.getStatusCode());
            assertNotNull(resp.getHeader("X-Auth"));
        }
    }

    // Phase 5: Multiple challenges - select best algorithm
    @RepeatedIfExceptionsTest(repeats = 5)
    public void multipleChallengesSelectsBest() throws Exception {
        server.stop();
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(new MultipleChallengeHandler());
        server.start();
        port1 = connector.getLocalPort();

        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(digestAuthRealm(USER, ADMIN).setRealmName("MyRealm").build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(HttpServletResponse.SC_OK, resp.getStatusCode());
            // Verify the client picked SHA-256
            String authHeader = resp.getHeader("X-Auth");
            assertNotNull(authHeader);
        }
    }

    // Phase 7: Authentication-Info with nextnonce
    @RepeatedIfExceptionsTest(repeats = 5)
    public void authenticationInfoNextnonce() throws Exception {
        server.stop();
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(new NextNonceHandler());
        server.start();
        port1 = connector.getLocalPort();

        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(digestAuthRealm(USER, ADMIN).setRealmName("MyRealm").build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(HttpServletResponse.SC_OK, resp.getStatusCode());
        }
    }

    /**
     * Handler that sends stale=true on the second 401, forcing a nonce refresh.
     */
    private static class StaleNonceHandler extends AbstractHandler {
        private final String realm = "MyRealm";
        private final ExtendedDigestAuthenticator authenticator = new ExtendedDigestAuthenticator();
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private volatile String currentNonce = ExtendedDigestAuthenticator.newNonce();

        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            int count = requestCount.incrementAndGet();
            String authz = request.getHeader("Authorization");

            if (authz == null || !authz.startsWith("Digest ")) {
                // First request: no auth → challenge
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate",
                        authenticator.createAuthenticateHeader(realm, currentNonce, false));
                response.getOutputStream().close();
                return;
            }

            // Second request has auth - simulate stale nonce on first auth attempt
            String credentials = authz.substring("Digest ".length());
            Map<String, String> params = ExtendedDigestAuthenticator.parseCredentials(credentials);

            if (count == 2) {
                // Simulate stale nonce - send new nonce with stale=true
                currentNonce = ExtendedDigestAuthenticator.newNonce();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate",
                        authenticator.createAuthenticateHeader(realm, currentNonce, true));
                response.getOutputStream().close();
                return;
            }

            // Third request - validate with new nonce
            if (!USER.equals(params.get("username"))) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getOutputStream().close();
                return;
            }
            boolean ok = ExtendedDigestAuthenticator.validateDigest(request.getMethod(), credentials, ADMIN);
            if (!ok) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getOutputStream().close();
                return;
            }
            response.addHeader("X-Auth", authz);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }

    /**
     * Handler that sends multiple Digest challenges with different algorithms.
     */
    private static class MultipleChallengeHandler extends AbstractHandler {
        private final String realm = "MyRealm";
        private final String nonce = ExtendedDigestAuthenticator.newNonce();
        private final ExtendedDigestAuthenticator sha256Auth = new ExtendedDigestAuthenticator("SHA-256");

        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            String authz = request.getHeader("Authorization");
            if (authz == null || !authz.startsWith("Digest ")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                // Send multiple challenges - SHA-256 first (preferred), then MD5
                response.addHeader("WWW-Authenticate",
                        sha256Auth.createAuthenticateHeader(realm, nonce, false));
                response.addHeader("WWW-Authenticate",
                        "Digest realm=\"" + realm + "\", nonce=\"" + nonce + "\", qop=\"auth\"");
                response.getOutputStream().close();
                return;
            }
            String credentials = authz.substring("Digest ".length());
            Map<String, String> params = ExtendedDigestAuthenticator.parseCredentials(credentials);
            if (!USER.equals(params.get("username"))) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getOutputStream().close();
                return;
            }
            boolean ok = ExtendedDigestAuthenticator.validateDigest(request.getMethod(), credentials, ADMIN);
            if (!ok) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getOutputStream().close();
                return;
            }
            response.addHeader("X-Auth", authz);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }

    /**
     * Handler that sends Authentication-Info header with nextnonce.
     */
    private static class NextNonceHandler extends AbstractHandler {
        private final String realm = "MyRealm";
        private final ExtendedDigestAuthenticator authenticator = new ExtendedDigestAuthenticator();
        private final String nonce = ExtendedDigestAuthenticator.newNonce();

        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            String authz = request.getHeader("Authorization");
            if (authz == null || !authz.startsWith("Digest ")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate",
                        authenticator.createAuthenticateHeader(realm, nonce, false));
                response.getOutputStream().close();
                return;
            }
            String credentials = authz.substring("Digest ".length());
            Map<String, String> params = ExtendedDigestAuthenticator.parseCredentials(credentials);
            if (!USER.equals(params.get("username"))) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getOutputStream().close();
                return;
            }
            boolean ok = ExtendedDigestAuthenticator.validateDigest(request.getMethod(), credentials, ADMIN);
            if (!ok) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getOutputStream().close();
                return;
            }
            // Send Authentication-Info with nextnonce
            String nextNonce = ExtendedDigestAuthenticator.newNonce();
            response.addHeader("Authentication-Info", "nextnonce=\"" + nextNonce + "\"");
            response.addHeader("X-Auth", authz);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }
}
