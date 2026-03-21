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

import io.github.artsok.RepeatedIfExceptionsTest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.scram.ScramEngine;
import org.asynchttpclient.scram.ScramMessageParser;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.asynchttpclient.Dsl.scramSha256AuthRealm;
import static org.asynchttpclient.test.TestUtils.ADMIN;
import static org.asynchttpclient.test.TestUtils.USER;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ScramAuthTest extends AbstractBasicTest {

    @Override
    @BeforeEach
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(new ScramAuthHandler());
        server.start();
        port1 = connector.getLocalPort();
        logger.info("Local HTTP server started successfully");
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ScramAuthHandler();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testScramSha256_fullExchange() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(scramSha256AuthRealm(USER, ADMIN).setRealmName("ScramRealm").build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(200, resp.getStatusCode());
            assertNotNull(resp.getHeader("X-Auth"));
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testScramSha256_wrongPassword() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(scramSha256AuthRealm(USER, "wrongpassword").setRealmName("ScramRealm").build())
                    .execute();
            Response resp = f.get(20, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(401, resp.getStatusCode());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testScramSha256_maxIterationCount() throws Exception {
        // Server uses 4096 iterations, client max is 100 — should fail
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(scramSha256AuthRealm(USER, ADMIN)
                            .setRealmName("ScramRealm")
                            .setMaxIterationCount(100) // Lower than server's 4096
                            .build())
                    .execute();
            Response resp = f.get(20, TimeUnit.SECONDS);
            assertNotNull(resp);
            // Should get 401 because client aborts the exchange
            assertEquals(401, resp.getStatusCode());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testScramSha256_missingAuthInfo() throws Exception {
        // Test with handler that doesn't send Authentication-Info
        server.stop();
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(new ScramAuthHandler(false)); // no auth-info
        server.start();
        port1 = connector.getLocalPort();

        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(scramSha256AuthRealm(USER, ADMIN).setRealmName("ScramRealm").build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(200, resp.getStatusCode());
            // 200 without Authentication-Info — warning logged, but request succeeds
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testScramSha256_malformedBase64InData() throws Exception {
        server.stop();
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(new MalformedBase64ScramHandler());
        server.start();
        port1 = connector.getLocalPort();

        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(scramSha256AuthRealm(USER, ADMIN).setRealmName("ScramRealm").build())
                    .execute();
            Response resp = f.get(20, TimeUnit.SECONDS);
            assertNotNull(resp);
            // Client should handle malformed base64 gracefully — no crash, returns 401
            assertEquals(401, resp.getStatusCode());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testScramSha256_quotedDataAttribute() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(scramSha256AuthRealm(USER, ADMIN).setRealmName("ScramRealm").build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(200, resp.getStatusCode());
            // The handler validates that data is properly quoted (Erratum 6558)
            assertEquals("quoted", resp.getHeader("X-Data-Quoted"));
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testScramSha256_proxyFullExchange() throws Exception {
        server.stop();
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(new ScramProxyHandler());
        server.start();
        int proxyPort = connector.getLocalPort();

        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost/")
                    .setProxyServer(proxyServer("localhost", proxyPort)
                            .setRealm(scramSha256AuthRealm(USER, ADMIN)
                                    .setRealmName("ScramRealm")
                                    .build())
                            .build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(200, resp.getStatusCode());
        }
    }

    /**
     * Server-side SCRAM-SHA-256 authenticator for testing.
     * Implements the full 3-step SCRAM handshake:
     * 1. Initial 401 with SCRAM challenge
     * 2. Second 401 with server-first-message
     * 3. 200 with Authentication-Info (server-final-message)
     */
    private static class ScramAuthHandler extends AbstractHandler {

        private static final String REALM = "ScramRealm";
        private static final byte[] SALT = Base64.getDecoder().decode("W22ZaJ0SNY7soEsUEjb6gQ==");
        private static final int ITERATIONS = 4096;

        private final boolean sendAuthInfo;

        // Session tracking: sid → ServerSession
        private final ConcurrentHashMap<String, ServerSession> sessions = new ConcurrentHashMap<>();

        ScramAuthHandler() {
            this(true);
        }

        ScramAuthHandler(boolean sendAuthInfo) {
            this.sendAuthInfo = sendAuthInfo;
        }

        static class ServerSession {
            final String fullNonce;
            final String clientFirstBare;

            ServerSession(String clientNonce, String serverNoncePart, String clientFirstBare) {
                this.fullNonce = clientNonce + serverNoncePart;
                this.clientFirstBare = clientFirstBare;
            }
        }

        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException {
            String authz = request.getHeader("Authorization");

            if (authz == null) {
                // Step 1: Send initial challenge
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", "SCRAM-SHA-256 realm=\"" + REALM + "\"");
                response.getOutputStream().close();
                return;
            }

            if (!authz.startsWith("SCRAM-SHA-256")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getOutputStream().close();
                return;
            }

            ScramMessageParser.ScramChallengeParams params = ScramMessageParser.parseWwwAuthenticateScram(authz);

            // Check if data attribute was properly quoted (Erratum 6558)
            boolean dataQuoted = authz.contains("data=\"");
            response.setHeader("X-Data-Quoted", dataQuoted ? "quoted" : "unquoted");

            if (params.sid == null && params.data != null) {
                // Step 2: Process client-first, send server-first
                String clientFirstFull = new String(Base64.getDecoder().decode(params.data), StandardCharsets.UTF_8);

                // Validate gs2-header
                if (!clientFirstFull.startsWith("n,,")) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getOutputStream().close();
                    return;
                }

                // Parse client-first-message
                String clientFirstBare = clientFirstFull.substring(3); // Remove "n,,"
                String clientNonce = null;
                String clientUsername = null;
                for (String part : clientFirstBare.split(",")) {
                    if (part.startsWith("r=")) {
                        clientNonce = part.substring(2);
                    } else if (part.startsWith("n=")) {
                        clientUsername = part.substring(2);
                    }
                }

                if (clientNonce == null || clientUsername == null) {
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    response.getOutputStream().close();
                    return;
                }

                // Verify username
                if (!USER.equals(clientUsername.replace("=3D", "=").replace("=2C", ","))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getOutputStream().close();
                    return;
                }

                // Generate server nonce and sid
                String serverNoncePart = ScramEngine.generateNonce(18);
                String sid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

                // Store session
                sessions.put(sid, new ServerSession(clientNonce, serverNoncePart, clientFirstBare));

                // Build server-first-message
                String fullNonce = clientNonce + serverNoncePart;
                String saltBase64 = Base64.getEncoder().encodeToString(SALT);
                String serverFirstMsg = "r=" + fullNonce + ",s=" + saltBase64 + ",i=" + ITERATIONS;
                String base64ServerFirst = Base64.getEncoder().encodeToString(serverFirstMsg.getBytes(StandardCharsets.UTF_8));

                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", "SCRAM-SHA-256 sid=" + sid + ", data=\"" + base64ServerFirst + "\"");
                response.getOutputStream().close();

            } else if (params.sid != null && params.data != null) {
                // Step 3: Process client-final, validate proof, send server-final
                ServerSession session = sessions.remove(params.sid);
                if (session == null) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getOutputStream().close();
                    return;
                }

                String clientFinalMsg = new String(Base64.getDecoder().decode(params.data), StandardCharsets.UTF_8);

                // Parse client-final
                String clientFinalNonce = null;
                String clientProofBase64 = null;
                for (String part : clientFinalMsg.split(",")) {
                    if (part.startsWith("r=")) {
                        clientFinalNonce = part.substring(2);
                    } else if (part.startsWith("p=")) {
                        clientProofBase64 = part.substring(2);
                    }
                }

                if (clientFinalNonce == null || clientProofBase64 == null) {
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    response.getOutputStream().close();
                    return;
                }

                // Validate nonce
                if (!clientFinalNonce.equals(session.fullNonce)) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getOutputStream().close();
                    return;
                }

                // Compute server-side values to verify client proof
                byte[] saltedPassword = ScramEngine.computeSaltedPassword(ADMIN, SALT, ITERATIONS);
                byte[] clientKey = ScramEngine.computeClientKey(saltedPassword);
                byte[] storedKey = ScramEngine.computeStoredKey(clientKey);
                byte[] serverKey = ScramEngine.computeServerKey(saltedPassword);

                // Build AuthMessage
                String saltBase64 = Base64.getEncoder().encodeToString(SALT);
                String serverFirstMsg = "r=" + session.fullNonce + ",s=" + saltBase64 + ",i=" + ITERATIONS;
                String clientFinalWithoutProof = "c=biws,r=" + session.fullNonce;
                String authMessage = session.clientFirstBare + "," + serverFirstMsg + "," + clientFinalWithoutProof;

                // Verify client proof
                byte[] clientSignature = ScramEngine.computeClientSignature(storedKey, authMessage);
                byte[] receivedProof = Base64.getDecoder().decode(clientProofBase64);
                byte[] recoveredClientKey = ScramEngine.xor(receivedProof.clone(), clientSignature);

                // StoredKey = H(ClientKey) — verify
                byte[] recoveredStoredKey = ScramEngine.hash(recoveredClientKey);
                boolean proofValid = Arrays.equals(storedKey, recoveredStoredKey);

                ScramEngine.zeroBytes(saltedPassword);

                if (!proofValid) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getOutputStream().close();
                    return;
                }

                // Compute ServerSignature for Authentication-Info
                byte[] serverSignature = ScramEngine.computeServerSignature(serverKey, authMessage);
                String serverFinal = "v=" + Base64.getEncoder().encodeToString(serverSignature);
                String base64ServerFinal = Base64.getEncoder().encodeToString(serverFinal.getBytes(StandardCharsets.UTF_8));

                response.setStatus(HttpServletResponse.SC_OK);
                response.addHeader("X-Auth", authz);
                if (sendAuthInfo) {
                    response.setHeader("Authentication-Info", "sid=" + params.sid + ", data=\"" + base64ServerFinal + "\"");
                }
                response.getOutputStream().flush();
                response.getOutputStream().close();

            } else {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getOutputStream().close();
            }
        }
    }

    /**
     * Proxy-side SCRAM-SHA-256 authenticator for testing the 407 proxy authentication flow.
     * Uses Proxy-Authorization/Proxy-Authenticate headers and 407 status codes.
     */
    private static class ScramProxyHandler extends AbstractHandler {

        private static final String REALM = "ScramRealm";
        private static final byte[] SALT = Base64.getDecoder().decode("W22ZaJ0SNY7soEsUEjb6gQ==");
        private static final int ITERATIONS = 4096;

        private final ConcurrentHashMap<String, ScramAuthHandler.ServerSession> sessions = new ConcurrentHashMap<>();

        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException {
            String authz = request.getHeader("Proxy-Authorization");

            if (authz == null) {
                response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
                response.setHeader("Proxy-Authenticate", "SCRAM-SHA-256 realm=\"" + REALM + "\"");
                response.setContentLength(0);
                response.getOutputStream().flush();
                response.getOutputStream().close();
                return;
            }

            if (!authz.startsWith("SCRAM-SHA-256")) {
                response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
                response.setContentLength(0);
                response.getOutputStream().flush();
                response.getOutputStream().close();
                return;
            }

            ScramMessageParser.ScramChallengeParams params = ScramMessageParser.parseWwwAuthenticateScram(authz);

            if (params.sid == null && params.data != null) {
                String clientFirstFull = new String(Base64.getDecoder().decode(params.data), StandardCharsets.UTF_8);

                if (!clientFirstFull.startsWith("n,,")) {
                    response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
                    response.setContentLength(0);
                    response.getOutputStream().flush();
                    response.getOutputStream().close();
                    return;
                }

                String clientFirstBare = clientFirstFull.substring(3);
                String clientNonce = null;
                String clientUsername = null;
                for (String part : clientFirstBare.split(",")) {
                    if (part.startsWith("r=")) {
                        clientNonce = part.substring(2);
                    } else if (part.startsWith("n=")) {
                        clientUsername = part.substring(2);
                    }
                }

                if (clientNonce == null || clientUsername == null) {
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    response.setContentLength(0);
                    response.getOutputStream().flush();
                    response.getOutputStream().close();
                    return;
                }

                if (!USER.equals(clientUsername.replace("=3D", "=").replace("=2C", ","))) {
                    response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
                    response.setContentLength(0);
                    response.getOutputStream().flush();
                    response.getOutputStream().close();
                    return;
                }

                String serverNoncePart = ScramEngine.generateNonce(18);
                String sid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

                sessions.put(sid, new ScramAuthHandler.ServerSession(clientNonce, serverNoncePart, clientFirstBare));

                String fullNonce = clientNonce + serverNoncePart;
                String saltBase64 = Base64.getEncoder().encodeToString(SALT);
                String serverFirstMsg = "r=" + fullNonce + ",s=" + saltBase64 + ",i=" + ITERATIONS;
                String base64ServerFirst = Base64.getEncoder().encodeToString(serverFirstMsg.getBytes(StandardCharsets.UTF_8));

                response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
                response.setHeader("Proxy-Authenticate", "SCRAM-SHA-256 sid=" + sid + ", data=\"" + base64ServerFirst + "\"");
                response.setContentLength(0);
                response.getOutputStream().flush();
                response.getOutputStream().close();

            } else if (params.sid != null && params.data != null) {
                ScramAuthHandler.ServerSession session = sessions.remove(params.sid);
                if (session == null) {
                    response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
                    response.setContentLength(0);
                    response.getOutputStream().flush();
                    response.getOutputStream().close();
                    return;
                }

                String clientFinalMsg = new String(Base64.getDecoder().decode(params.data), StandardCharsets.UTF_8);

                String clientFinalNonce = null;
                String clientProofBase64 = null;
                for (String part : clientFinalMsg.split(",")) {
                    if (part.startsWith("r=")) {
                        clientFinalNonce = part.substring(2);
                    } else if (part.startsWith("p=")) {
                        clientProofBase64 = part.substring(2);
                    }
                }

                if (clientFinalNonce == null || clientProofBase64 == null) {
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    response.setContentLength(0);
                    response.getOutputStream().flush();
                    response.getOutputStream().close();
                    return;
                }

                if (!clientFinalNonce.equals(session.fullNonce)) {
                    response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
                    response.setContentLength(0);
                    response.getOutputStream().flush();
                    response.getOutputStream().close();
                    return;
                }

                byte[] saltedPassword = ScramEngine.computeSaltedPassword(ADMIN, SALT, ITERATIONS);
                byte[] clientKey = ScramEngine.computeClientKey(saltedPassword);
                byte[] storedKey = ScramEngine.computeStoredKey(clientKey);
                byte[] serverKey = ScramEngine.computeServerKey(saltedPassword);

                String saltBase64 = Base64.getEncoder().encodeToString(SALT);
                String serverFirstMsg = "r=" + session.fullNonce + ",s=" + saltBase64 + ",i=" + ITERATIONS;
                String clientFinalWithoutProof = "c=biws,r=" + session.fullNonce;
                String authMessage = session.clientFirstBare + "," + serverFirstMsg + "," + clientFinalWithoutProof;

                byte[] clientSignature = ScramEngine.computeClientSignature(storedKey, authMessage);
                byte[] receivedProof = Base64.getDecoder().decode(clientProofBase64);
                byte[] recoveredClientKey = ScramEngine.xor(receivedProof.clone(), clientSignature);

                byte[] recoveredStoredKey = ScramEngine.hash(recoveredClientKey);
                boolean proofValid = Arrays.equals(storedKey, recoveredStoredKey);

                ScramEngine.zeroBytes(saltedPassword);

                if (!proofValid) {
                    response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
                    response.setContentLength(0);
                    response.getOutputStream().flush();
                    response.getOutputStream().close();
                    return;
                }

                byte[] serverSignature = ScramEngine.computeServerSignature(serverKey, authMessage);
                String serverFinal = "v=" + Base64.getEncoder().encodeToString(serverSignature);
                String base64ServerFinal = Base64.getEncoder().encodeToString(serverFinal.getBytes(StandardCharsets.UTF_8));

                response.setStatus(HttpServletResponse.SC_OK);
                response.setHeader("Proxy-Authentication-Info", "sid=" + params.sid + ", data=\"" + base64ServerFinal + "\"");
                response.setContentLength(0);
                response.getOutputStream().flush();
                response.getOutputStream().close();

            } else {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentLength(0);
                response.getOutputStream().flush();
                response.getOutputStream().close();
            }
        }
    }

    /**
     * Handler that sends malformed base64 in the SCRAM data attribute (step 2).
     * Used to test that the client handles IllegalArgumentException from Base64 gracefully.
     */
    private static class MalformedBase64ScramHandler extends AbstractHandler {

        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException {
            String authz = request.getHeader("Authorization");

            if (authz == null) {
                // Step 1: Send initial challenge
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", "SCRAM-SHA-256 realm=\"ScramRealm\"");
                response.getOutputStream().close();
                return;
            }

            if (!authz.startsWith("SCRAM-SHA-256")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getOutputStream().close();
                return;
            }

            ScramMessageParser.ScramChallengeParams params = ScramMessageParser.parseWwwAuthenticateScram(authz);

            if (params.sid == null && params.data != null) {
                // Step 2: Send malformed base64 in data attribute
                String sid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate",
                        "SCRAM-SHA-256 sid=" + sid + ", data=\"!!!invalid-base64!!!\"");
                response.getOutputStream().close();
            } else {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getOutputStream().close();
            }
        }
    }
}
