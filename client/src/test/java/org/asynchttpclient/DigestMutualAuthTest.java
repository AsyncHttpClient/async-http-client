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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.asynchttpclient.test.ExtendedDigestAuthenticator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.digestAuthRealm;
import static org.asynchttpclient.test.TestUtils.ADMIN;
import static org.asynchttpclient.test.TestUtils.USER;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 7616 §3.5 mutual authentication: the {@code Authentication-Info: rspauth} value proves the server
 * knows the shared secret. A present-but-invalid {@code rspauth} means the server failed mutual auth, so
 * the client must not deliver the response as an authenticated success — it aborts (mirrors the SCRAM
 * ServerSignature enforcement in #2235). An absent header stays warn-only.
 */
public class DigestMutualAuthTest extends AbstractBasicTest {

    @Override
    @BeforeEach
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(new RspAuthHandler(false));
        server.start();
        port1 = connector.getLocalPort();
        logger.info("Local HTTP server started successfully");
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new RspAuthHandler(false);
    }

    @Test
    public void validRspAuthIsAccepted() throws Exception {
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

    /**
     * An honest server that answers the authenticated request with a same-origin redirect. The realm the
     * future carries still holds the pre-redirect URI — {@code Redirect30xInterceptor} only clears the realm
     * when it strips credentials, which a same-origin redirect does not — so an expected rspauth derived from
     * that realm is computed over the wrong {@code uri} and cannot match what the server signed. Verification
     * has to use the parameters actually sent.
     */
    @Test
    public void validRspAuthIsAcceptedAcrossSameOriginRedirect() throws Exception {
        restartServer(new RedirectingRspAuthHandler());

        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + "/start")
                    .setFollowRedirect(true)
                    .setRealm(digestAuthRealm(USER, ADMIN).setRealmName("MyRealm").build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(HttpServletResponse.SC_OK, resp.getStatusCode());
            // The rspauth that was verified belongs to the request sent to the redirect target, not to the
            // URI the exchange started on.
            assertTrue(resp.getHeader("X-Auth").contains("uri=\"/final\""),
                    "expected the final request to carry uri=\"/final\" but got: " + resp.getHeader("X-Auth"));
        }
    }

    /**
     * Preemptive Digest: the credentials go out on the first request, so the realm the future carries has no
     * {@code uri} at all and an expected rspauth derived from it hashes {@code H(":")}. An honest server must
     * not be rejected for that.
     */
    @Test
    public void preemptiveDigestIsNotSpuriouslyRejected() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(digestAuthRealm(USER, ADMIN)
                            .setRealmName("MyRealm")
                            .setNonce(ExtendedDigestAuthenticator.newNonce())
                            .setQop("auth")
                            .setUsePreemptiveAuth(true)
                            .build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(HttpServletResponse.SC_OK, resp.getStatusCode());
            // No 401 round trip happened: the very first request carried the credentials.
            assertNotNull(resp.getHeader("X-Auth"));
        }
    }

    @Test
    public void invalidRspAuthIsRejected() throws Exception {
        // Server completes the digest handshake but returns an rspauth it could not have computed without the
        // shared secret. RFC 7616 §3.5 requires the client to consider the exchange unsuccessful.
        restartServer(new RspAuthHandler(true));

        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(digestAuthRealm(USER, ADMIN).setRealmName("MyRealm").build())
                    .execute();
            ExecutionException ex = assertThrows(ExecutionException.class, () -> f.get(20, TimeUnit.SECONDS));
            assertNotNull(ex.getCause());
            assertTrue(ex.getCause().getMessage() != null && ex.getCause().getMessage().contains("rspauth"),
                    "expected an rspauth verification failure but got: " + ex.getCause());
        }
    }

    /**
     * RFC 7616 Section 3.5 defines {@code A2} for {@code qop=auth-int} as {@code ":" request-uri ":"
     * H(entity-body)} over the <em>response</em> entity-body. That body has not arrived when the {@code
     * Authentication-Info} header is processed, so the expected rspauth cannot be derived there at all.
     * <p>
     * The client used to negotiate auth-int anyway and then report "cannot verify", which delivered the
     * response with mutual authentication silently skipped for the whole exchange. Since the peer chooses
     * the challenge, offering {@code qop="auth-int"} on its own was a one-word way to switch mutual
     * authentication off. The client now declines to negotiate a mode whose rspauth it cannot check.
     * <p>
     * Interoperability cost, deliberately accepted: a server offering auth-int and nothing else can no
     * longer be authenticated against. {@code auth,auth-int} is unaffected, because auth is preferred and
     * its rspauth is verified.
     */
    @Test
    public void anAuthIntOnlyChallengeIsNotAnsweredWithAuthInt() throws Exception {
        restartServer(new AuthIntRspAuthHandler());

        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                    .setRealm(digestAuthRealm(USER, ADMIN).setRealmName("MyRealm").build())
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            // The server only accepts qop=auth-int, so declining it means the exchange is not authenticated.
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, resp.getStatusCode(),
                    "an auth-int-only challenge must not be answered, since its rspauth cannot be verified");
            String sent = resp.getHeader("X-Auth");
            assertTrue(sent == null || !sent.contains("qop=auth-int"),
                    "the client must not negotiate auth-int: " + sent);
        }
    }

    /**
     * The {@code algorithm} the client verifies against is echoed from the server's own challenge, so the
     * server picks its spelling. {@code MD5-SESS} passed the (case-insensitive) support gate but was then
     * stripped case-sensitively, so it reached the digest pool intact, threw, and was reported as "cannot
     * verify" — a one-word opt-out of mutual authentication that any server could take.
     */
    @Test
    public void invalidRspAuthIsRejectedWhateverTheSpellingOfTheAlgorithm() throws Exception {
        for (String spelling : new String[]{"MD5-SESS", "MD5-Sess", "MD5-sess", "SHA-256-SESS"}) {
            restartServer(new SessRspAuthHandler(spelling, true));

            try (AsyncHttpClient client = asyncHttpClient()) {
                Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                        .setRealm(digestAuthRealm(USER, ADMIN).setRealmName("MyRealm").build())
                        .execute();
                ExecutionException ex = assertThrows(ExecutionException.class, () -> f.get(20, TimeUnit.SECONDS),
                        "a corrupt rspauth was accepted for algorithm=" + spelling);
                assertNotNull(ex.getCause());
                assertTrue(ex.getCause().getMessage() != null && ex.getCause().getMessage().contains("rspauth"),
                        "expected an rspauth verification failure for algorithm=" + spelling + " but got: " + ex.getCause());
            }
        }
    }

    /**
     * The other half of {@link #invalidRspAuthIsRejectedWhateverTheSpellingOfTheAlgorithm()}: an honest
     * session-variant server must still be accepted, so the spellings above are genuinely being verified
     * rather than uniformly rejected.
     */
    @Test
    public void validSessionVariantRspAuthIsAccepted() throws Exception {
        for (String spelling : new String[]{"MD5-SESS", "MD5-sess", "SHA-256-SESS"}) {
            restartServer(new SessRspAuthHandler(spelling, false));

            try (AsyncHttpClient client = asyncHttpClient()) {
                Future<Response> f = client.prepareGet("http://localhost:" + port1 + '/')
                        .setRealm(digestAuthRealm(USER, ADMIN).setRealmName("MyRealm").build())
                        .execute();
                Response resp = f.get(60, TimeUnit.SECONDS);
                assertNotNull(resp);
                assertEquals(HttpServletResponse.SC_OK, resp.getStatusCode(), "algorithm=" + spelling);
                assertNotNull(resp.getHeader("X-Auth"), "algorithm=" + spelling);
            }
        }
    }

    private void restartServer(AbstractHandler handler) throws Exception {
        server.stop();
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(handler);
        server.start();
        port1 = connector.getLocalPort();
    }

    /**
     * Authenticates every request the same way {@link RspAuthHandler} does, but answers {@code /start} with
     * a same-origin redirect to {@code /final} so the client authenticates twice against two different URIs.
     */
    private static class RedirectingRspAuthHandler extends AbstractHandler {
        private final RspAuthHandler delegate = new RspAuthHandler(false);

        @Override
        public void handle(String target, Request r, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            if ("/start".equals(target)) {
                // Redirect only once the request is authenticated, so both hops carry credentials.
                String authz = request.getHeader("Authorization");
                if (authz != null && authz.startsWith("Digest ")) {
                    response.setHeader("Location", "/final");
                    delegate.handle(target, r, request, new StatusOverridingResponse(response, HttpServletResponse.SC_FOUND));
                    return;
                }
            }
            delegate.handle(target, r, request, response);
        }
    }

    /**
     * Lets {@link RspAuthHandler} write its {@code Authentication-Info} while the wrapper decides the status
     * line, so the redirect hop is otherwise byte-for-byte the response an honest server sends.
     */
    private static class StatusOverridingResponse extends HttpServletResponseWrapper {
        private final int status;

        StatusOverridingResponse(HttpServletResponse response, int status) {
            super(response);
            this.status = status;
        }

        @Override
        public void setStatus(int ignored) {
            super.setStatus(status);
        }
    }

    /**
     * A server that challenges with one of the RFC 7616 Section 3.3 session variants, in whatever spelling it
     * is handed. It does the full {@code -sess} math itself: HA1 = H(H(user:realm:pass) ":" nonce ":" cnonce),
     * on both the request it validates and the rspauth it signs.
     */
    private static class SessRspAuthHandler extends AbstractHandler {
        private final String realm = "MyRealm";
        private final String advertisedAlgorithm;
        private final String hashAlgorithm;
        private final String nonce = ExtendedDigestAuthenticator.newNonce();
        private final boolean corruptRspAuth;

        SessRspAuthHandler(String advertisedAlgorithm, boolean corruptRspAuth) {
            this.advertisedAlgorithm = advertisedAlgorithm;
            this.hashAlgorithm = ExtendedDigestAuthenticator.findAlgorithm(advertisedAlgorithm);
            this.corruptRspAuth = corruptRspAuth;
        }

        @Override
        public void handle(String target, Request r, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            String authz = request.getHeader("Authorization");
            if (authz == null || !authz.startsWith("Digest ")) {
                challenge(response);
                return;
            }

            Map<String, String> p = ExtendedDigestAuthenticator.parseCredentials(authz.substring("Digest ".length()));
            try {
                String expectedResponse = kd(p, RspAuthHandler.hex(digest((request.getMethod() + ':' + p.get("uri"))
                        .getBytes(StandardCharsets.ISO_8859_1))));
                if (!USER.equals(p.get("username")) || !expectedResponse.equalsIgnoreCase(p.get("response"))) {
                    challenge(response);
                    return;
                }

                String rspauth = kd(p, RspAuthHandler.hex(digest((':' + p.get("uri")).getBytes(StandardCharsets.ISO_8859_1))));
                response.addHeader("Authentication-Info", "rspauth=\"" + (corruptRspAuth ? RspAuthHandler.mangle(rspauth) : rspauth) + '"');
                response.addHeader("X-Auth", authz);
                response.setStatus(HttpServletResponse.SC_OK);
                response.getOutputStream().flush();
                response.getOutputStream().close();
            } catch (Exception e) {
                throw new ServletException(e);
            }
        }

        private void challenge(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Digest realm=\"" + realm + "\", nonce=\"" + nonce
                    + "\", algorithm=" + advertisedAlgorithm + ", qop=\"auth\"");
            response.getOutputStream().close();
        }

        private String kd(Map<String, String> p, String ha2) throws Exception {
            String ha1 = RspAuthHandler.hex(digest((USER + ':' + realm + ':' + ADMIN).getBytes(StandardCharsets.ISO_8859_1)));
            // The session variant folds the nonce and cnonce into HA1.
            ha1 = RspAuthHandler.hex(digest((ha1 + ':' + nonce + ':' + p.get("cnonce")).getBytes(StandardCharsets.ISO_8859_1)));
            String input = ha1 + ':' + nonce + ':' + p.get("nc") + ':' + p.get("cnonce") + ':' + p.get("qop") + ':' + ha2;
            return RspAuthHandler.hex(digest(input.getBytes(StandardCharsets.ISO_8859_1)));
        }

        private byte[] digest(byte[] bytes) throws Exception {
            return ExtendedDigestAuthenticator.getMessageDigest(hashAlgorithm).digest(bytes);
        }
    }

    /**
     * An honest {@code qop="auth-int"} server. It offers auth-int alone, validates the request digest with
     * {@code A2 = Method ":" request-uri ":" H(request-body)}, and signs its answer with
     * {@code A2 = ":" request-uri ":" H(response-body)} — both straight out of RFC 7616 §3.4.2/§3.5, not
     * copied from the client's implementation. Everything it emits is byte-for-byte what a conformant server
     * sends, so a client that aborts here is rejecting a correct peer.
     */
    private static class AuthIntRspAuthHandler extends AbstractHandler {
        static final String BODY = "auth-int body that the rspauth is computed over";

        private final String realm = "MyRealm";
        private final String nonce = ExtendedDigestAuthenticator.newNonce();

        @Override
        public void handle(String target, Request r, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            String authz = request.getHeader("Authorization");
            if (authz == null || !authz.startsWith("Digest ")) {
                challenge(response);
                return;
            }

            Map<String, String> p = ExtendedDigestAuthenticator.parseCredentials(authz.substring("Digest ".length()));
            try {
                // A GET carries no body, so H(entity-body) over the request is H("").
                String requestBodyHash = md5Hex(new byte[0]);
                String a2 = request.getMethod() + ':' + p.get("uri") + ':' + requestBodyHash;
                if (!"auth-int".equals(p.get("qop"))
                        || !USER.equals(p.get("username"))
                        || !kd(p, md5Hex(a2.getBytes(StandardCharsets.ISO_8859_1))).equalsIgnoreCase(p.get("response"))) {
                    challenge(response);
                    return;
                }

                byte[] body = BODY.getBytes(StandardCharsets.ISO_8859_1);
                // RFC 7616 §3.5: for auth-int the rspauth's A2 is ":" request-uri ":" H(response entity-body).
                String responseA2 = ':' + p.get("uri") + ':' + md5Hex(body);
                String rspauth = kd(p, md5Hex(responseA2.getBytes(StandardCharsets.ISO_8859_1)));

                response.addHeader("Authentication-Info", "rspauth=\"" + rspauth + "\", qop=auth-int");
                response.addHeader("X-Auth", authz);
                response.setStatus(HttpServletResponse.SC_OK);
                response.getOutputStream().write(body);
                response.getOutputStream().close();
            } catch (Exception e) {
                throw new ServletException(e);
            }
        }

        private void challenge(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            // auth-int alone: Realm.Builder.parseRawQop prefers "auth" whenever both are offered.
            response.setHeader("WWW-Authenticate", "Digest realm=\"" + realm + "\", nonce=\"" + nonce
                    + "\", algorithm=MD5, qop=\"auth-int\"");
            response.getOutputStream().close();
        }

        /** {@code KD(H(A1), nonce ":" nc ":" cnonce ":" qop ":" H(A2))}. */
        private String kd(Map<String, String> p, String ha2) throws Exception {
            String ha1 = md5Hex((USER + ':' + realm + ':' + ADMIN).getBytes(StandardCharsets.ISO_8859_1));
            String input = ha1 + ':' + nonce + ':' + p.get("nc") + ':' + p.get("cnonce") + ':' + p.get("qop") + ':' + ha2;
            return md5Hex(input.getBytes(StandardCharsets.ISO_8859_1));
        }

        private static String md5Hex(byte[] bytes) throws Exception {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder(32);
            for (byte b : md.digest(bytes)) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }
    }

    /**
     * Digest handler that, on a successful exchange, returns an {@code Authentication-Info} header carrying
     * either a correctly computed {@code rspauth} or a corrupted one.
     */
    private static class RspAuthHandler extends AbstractHandler {
        private final String realm = "MyRealm";
        private final ExtendedDigestAuthenticator authenticator = new ExtendedDigestAuthenticator();
        private final String nonce = ExtendedDigestAuthenticator.newNonce();
        private final boolean corruptRspAuth;

        RspAuthHandler(boolean corruptRspAuth) {
            this.corruptRspAuth = corruptRspAuth;
        }

        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            String authz = request.getHeader("Authorization");
            if (authz == null || !authz.startsWith("Digest ")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", authenticator.createAuthenticateHeader(realm, nonce, false));
                response.getOutputStream().close();
                return;
            }

            String credentials = authz.substring("Digest ".length());
            Map<String, String> params = ExtendedDigestAuthenticator.parseCredentials(credentials);
            if (!USER.equals(params.get("username"))
                    || !ExtendedDigestAuthenticator.validateDigest(request.getMethod(), credentials, ADMIN)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", authenticator.createAuthenticateHeader(realm, nonce, false));
                response.getOutputStream().close();
                return;
            }

            String rspauth;
            try {
                rspauth = computeRspAuth(params, ADMIN);
            } catch (Exception e) {
                throw new ServletException(e);
            }
            if (corruptRspAuth) {
                rspauth = mangle(rspauth);
            }

            response.addHeader("Authentication-Info", "rspauth=\"" + rspauth + '"');
            response.addHeader("X-Auth", authz);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }

        /**
         * Server-side rspauth per RFC 7616 §3.5: H(HA1 : nonce : nc : cnonce : qop : H(":" + uri)) using MD5.
         * A2 carries no method, only the URI — and, under {@code qop=auth}, no entity-body hash either.
         */
        private static String computeRspAuth(Map<String, String> p, String password) throws Exception {
            MessageDigest md = MessageDigest.getInstance("MD5");
            String ha1 = hex(md.digest((p.get("username") + ':' + p.get("realm") + ':' + password)
                    .getBytes(StandardCharsets.ISO_8859_1)));
            md.reset();
            String ha2 = hex(md.digest((':' + p.get("uri")).getBytes(StandardCharsets.ISO_8859_1)));
            md.reset();
            String qop = p.get("qop");
            String kd = qop != null
                    ? ha1 + ':' + p.get("nonce") + ':' + p.get("nc") + ':' + p.get("cnonce") + ':' + qop + ':' + ha2
                    : ha1 + ':' + p.get("nonce") + ':' + ha2;
            return hex(md.digest(kd.getBytes(StandardCharsets.ISO_8859_1)));
        }

        private static String mangle(String rspauth) {
            // Flip the first hex nibble so the value is present, well-formed, but wrong.
            char first = rspauth.charAt(0);
            char replacement = first == '0' ? '1' : '0';
            return replacement + rspauth.substring(1);
        }

        private static String hex(byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }
    }
}
