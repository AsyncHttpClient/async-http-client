/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
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

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.digestAuthRealm;
import static org.asynchttpclient.test.TestUtils.ADMIN;
import static org.asynchttpclient.test.TestUtils.USER;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class DigestAuthTest extends AbstractBasicTest {

    @Override
    @BeforeEach
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        String algorithm = null;
        String currentTest = System.getProperty("test.name");
        if (currentTest != null) {
            if (currentTest.contains("Sha256")) {
                algorithm = "SHA-256";
            } else if (currentTest.contains("Sha512_256")) {
                algorithm = "SHA-512-256";
            }
        }
        server.setHandler(new DigestAuthHandler(algorithm));
        server.start();
        port1 = connector.getLocalPort();
        logger.info("Local HTTP server started successfully");
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new SimpleHandler();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void digestAuthTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(digestAuthRealm(USER, ADMIN).setRealmName("MyRealm").build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(resp.getHeader("X-Auth"));
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void digestAuthTestWithoutScheme() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(digestAuthRealm(USER, ADMIN).setRealmName("MyRealm").build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(resp.getHeader("X-Auth"));
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void digestAuthNegativeTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(digestAuthRealm("fake", ADMIN).build())
                    .execute();
            Response resp = f.get(20, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), 401);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void digestAuthSha256Test() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(digestAuthRealm(USER, ADMIN)
                            .setRealmName("MyRealm")
                            .setAlgorithm("SHA-256")
                            .build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(resp.getHeader("X-Auth"));
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void digestAuthSha512_256Test() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(digestAuthRealm(USER, ADMIN)
                            .setRealmName("MyRealm")
                            .setAlgorithm("SHA-512-256")
                            .build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(resp.getHeader("X-Auth"));
        }
    }

    private static class DigestAuthHandler extends AbstractHandler {
        private final String realm = "MyRealm";
        private final String user = USER;
        private final String password = ADMIN;
        private final ExtendedDigestAuthenticator authenticator;
        private final String nonce;
        private final String algorithm;

        DigestAuthHandler(String algorithm) {
            this.algorithm = algorithm;
            authenticator = new ExtendedDigestAuthenticator(algorithm);
            nonce = ExtendedDigestAuthenticator.newNonce();
        }

        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            String authz = request.getHeader("Authorization");
            if (authz == null || !authz.startsWith("Digest ")) {
                // Challenge
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", authenticator.createAuthenticateHeader(realm, nonce, false));
                response.getOutputStream().close();
                return;
            }
            // Validate
            String credentials = authz.substring("Digest ".length());
            Map<String, String> params = ExtendedDigestAuthenticator.parseCredentials(credentials);
            String username = params.get("username");
            if (!user.equals(username)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", authenticator.createAuthenticateHeader(realm, nonce, true));
                response.getOutputStream().close();
                return;
            }
            boolean ok = ExtendedDigestAuthenticator.validateDigest(request.getMethod(), credentials, password);
            if (!ok) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", authenticator.createAuthenticateHeader(realm, nonce, true));
                response.getOutputStream().close();
                return;
            }
            // Success
            response.addHeader("X-Auth", authz);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }

    private static class SimpleHandler extends AbstractHandler {

        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            response.addHeader("X-Auth", request.getHeader("Authorization"));
            response.setStatus(200);
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }
}
