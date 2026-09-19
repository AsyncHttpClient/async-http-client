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
import org.asynchttpclient.handler.MaxRedirectException;
import org.asynchttpclient.handler.RedirectRefusedException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforcement of {@link RedirectPolicy} in {@code Redirect30xInterceptor}.
 * <p>
 * {@code setUpGlobal()} is overridden but stays on {@link BeforeAll}, so the class gets ONE server and the
 * inherited {@code @AfterAll tearDownGlobal()} stops it. Deliberately not the shape
 * {@code HttpToHttpsRedirectTest} uses: re-annotating the override {@code @BeforeEach} while the teardown
 * stays {@code @AfterAll} starts a server - here one with a TLS connector - per test method and stops only the
 * last.
 */
public class RedirectPolicyTest extends AbstractBasicTest {

    private static final String REDIRECT_TO = "X-Redirect-To";
    private static final String REDIRECT_STATUS = "X-Redirect-Status";

    private final AtomicBoolean targetHit = new AtomicBoolean();
    private final AtomicReference<String> methodOnTarget = new AtomicReference<>();
    private final AtomicReference<String> bodyOnTarget = new AtomicReference<>();
    private final AtomicReference<String> customHeaderOnTarget = new AtomicReference<>();
    private final AtomicReference<String> authOnTarget = new AtomicReference<>();

    @Override
    @BeforeAll
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector plain = addHttpConnector(server);
        ServerConnector secure = addHttpsConnector(server);
        server.setHandler(new RedirectHandler());
        server.start();
        port1 = plain.getLocalPort();
        port2 = secure.getLocalPort();
        logger.info("Local HTTP/HTTPS server started: plain={} secure={}", port1, port2);
    }

    @BeforeEach
    public void resetCaptures() {
        targetHit.set(false);
        methodOnTarget.set(null);
        bodyOnTarget.set(null);
        customHeaderOnTarget.set(null);
        authOnTarget.set(null);
    }

    private class RedirectHandler extends AbstractHandler {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException {
            // Drain before responding: answering while the client is still writing a streaming body makes the
            // write fail first and the exchange report that instead of the policy refusal. The security
            // property holds either way, but the reported cause would be racy.
            byte[] drained = readFully(request);
            if (target.endsWith("/hop1")) {
                // same-origin first hop, so a per-request policy must still be in force at hop 2
                response.setStatus(307);
                response.setHeader("Location", "http://localhost:" + port1 + "/hop2");
                response.getOutputStream().write("moved".getBytes(StandardCharsets.UTF_8));
            } else if (target.endsWith("/hop2")) {
                response.setStatus(307);
                response.setHeader("Location", "http://127.0.0.1:" + port1 + "/target");
                response.getOutputStream().write("moved".getBytes(StandardCharsets.UTF_8));
            } else if (target.endsWith("/redirect")) {
                String location = request.getHeader(REDIRECT_TO);
                String status = request.getHeader(REDIRECT_STATUS);
                response.setStatus(status == null ? 302 : Integer.parseInt(status));
                response.setHeader("Location", location);
                // A short entity so the delivered-3xx path, when one is taken, has something to drain.
                response.getOutputStream().write("moved".getBytes(StandardCharsets.UTF_8));
            } else {
                targetHit.set(true);
                methodOnTarget.set(request.getMethod());
                customHeaderOnTarget.set(request.getHeader("X-Api-Key"));
                authOnTarget.set(request.getHeader("Authorization"));
                bodyOnTarget.set(new String(drained, StandardCharsets.UTF_8));
                response.setStatus(200);
                response.getOutputStream().write("ok".getBytes(StandardCharsets.UTF_8));
            }
            response.getOutputStream().flush();
            baseRequest.setHandled(true);
        }
    }

    private static byte[] readFully(HttpServletRequest request) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read;
        while ((read = request.getInputStream().read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private String plainRedirect() {
        return "http://localhost:" + port1 + "/redirect";
    }

    private String secureRedirect() {
        return "https://localhost:" + port2 + "/redirect";
    }

    private String plainTarget() {
        return "http://localhost:" + port1 + "/target";
    }

    private String secureTarget() {
        return "https://localhost:" + port2 + "/target";
    }

    /**
     * Same port, different host string - cross-origin under any RFC 6454 reading, and the trick
     * {@code RedirectBodyTest} already uses for a second origin on one server.
     */
    private String otherOriginTarget() {
        return "http://127.0.0.1:" + port1 + "/target";
    }

    private static DefaultAsyncHttpClientConfig.Builder policy(RedirectPolicy redirectPolicy) {
        return config()
                .setFollowRedirect(true)
                .setUseInsecureTrustManager(true)
                .setMaxRedirects(5)
                .setRedirectPolicy(redirectPolicy);
    }

    private static RedirectRefusedException assertRefused(ExecutionException e) {
        return assertInstanceOf(RedirectRefusedException.class, e.getCause(),
                "a refused redirect must fail the future with RedirectRefusedException, got: " + e.getCause());
    }

    // ---------------------------------------------------------------- downgrade arm

    @Test
    public void downgradeIsFollowedUnderAllowAll() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.ALLOW_ALL))) {
            Response response = client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertTrue(targetHit.get(), "ALLOW_ALL must keep following the downgrade");
        }
    }

    @Test
    public void downgradeOnABodylessGetIsRefused() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertRefused(e);
            assertFalse(targetHit.get(), "the plaintext target must never be reached");
        }
    }

    /**
     * The case a body-presence refusal shape would have got wrong: an unsafe method with no content. Delivering
     * the 3xx here would read as success to any {@code status < 400} check while the DELETE never happened.
     */
    @Test
    public void downgradeOnABodylessDeleteIsRefused() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client.prepareDelete(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setHeader(REDIRECT_STATUS, "307")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertRefused(e);
            assertFalse(targetHit.get());
        }
    }

    @Test
    public void downgradeOnABodyCarryingPutIsRefused() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client.preparePut(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setHeader(REDIRECT_STATUS, "308")
                    .setBody("sensitive-content")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertRefused(e);
            assertFalse(targetHit.get());
            assertNull(bodyOnTarget.get(), "the content must not have reached the plaintext target");
        }
    }

    /**
     * A refusal must not reach the AsyncHandler as a response at all - that is the whole difference from the
     * shape the JDK uses, and what stops {@code BodyDeferringAsyncHandler} writing a 3xx body into a caller's
     * stream and {@code ResumableAsyncHandler} reporting completion.
     */
    @Test
    public void downgradeRefusalDeliversNoResponseToTheHandler() throws Exception {
        AtomicBoolean statusSeen = new AtomicBoolean();
        AtomicBoolean bodySeen = new AtomicBoolean();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            AsyncHandler<Void> handler = new AsyncHandler<Void>() {
                @Override
                public State onStatusReceived(HttpResponseStatus responseStatus) {
                    statusSeen.set(true);
                    return State.CONTINUE;
                }

                @Override
                public State onHeadersReceived(io.netty.handler.codec.http.HttpHeaders headers) {
                    return State.CONTINUE;
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
                    bodySeen.set(true);
                    return State.CONTINUE;
                }

                @Override
                public void onThrowable(Throwable t) {
                    thrown.set(t);
                }

                @Override
                public Void onCompleted() {
                    return null;
                }
            };

            assertThrows(ExecutionException.class, () -> client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .execute(handler).get(TIMEOUT, TimeUnit.SECONDS));

            assertFalse(statusSeen.get(), "the refused 3xx must not be delivered as a status");
            assertFalse(bodySeen.get(), "the refused 3xx body must not be delivered");
            assertInstanceOf(RedirectRefusedException.class, thrown.get());
        }
    }

    @ParameterizedTest(name = "an http to https upgrade is followed under {0}")
    @EnumSource(RedirectPolicy.class)
    public void upgradeIsFollowedUnderEveryPolicy(RedirectPolicy redirectPolicy) throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(redirectPolicy))) {
            Response response = client.prepareGet(plainRedirect())
                    .setHeader(REDIRECT_TO, secureTarget())
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertTrue(targetHit.get(), "an upgrade must never be refused by the downgrade arm");
        }
    }

    // ---------------------------------------------------------------- cross-origin arm

    @Test
    public void crossOriginBodyReplayIsFollowedUnderTheDowngradeOnlyPolicy() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            Response response = client.preparePut(plainRedirect())
                    .setHeader(REDIRECT_TO, otherOriginTarget())
                    .setHeader(REDIRECT_STATUS, "307")
                    .setBody("sensitive-content")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertEquals("sensitive-content", bodyOnTarget.get(), "the downgrade arm alone must not gate content");
        }
    }

    @Test
    public void crossOriginBodyReplayIsRefusedUnderTheStrictPolicy() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(
                policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY))) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client.preparePut(plainRedirect())
                    .setHeader(REDIRECT_TO, otherOriginTarget())
                    .setHeader(REDIRECT_STATUS, "307")
                    .setBody("sensitive-content")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertRefused(e);
            assertFalse(targetHit.get(), "the other origin must never receive the request");
        }
    }

    /**
     * {@code keepBody} is true for every GET on 301/302/307/308, so gating the arm on it alone would refuse
     * essentially every cross-origin redirect on the web. It must gate on content actually being present.
     */
    @Test
    public void crossOriginBodylessGetIsFollowedUnderTheStrictPolicy() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(
                policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY))) {
            Response response = client.prepareGet(plainRedirect())
                    .setHeader(REDIRECT_TO, otherOriginTarget())
                    .setHeader(REDIRECT_STATUS, "307")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertTrue(targetHit.get(), "a bodiless cross-origin hop carries nothing to protect");
        }
    }

    /**
     * The commonest redirect on the web: a form POST answered 302, rewritten to a bodiless GET. The content is
     * already dropped, so there is nothing to replay and the arm must not fire.
     */
    @Test
    public void crossOriginPostRewrittenToGetIsFollowedUnderTheStrictPolicy() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(
                policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY))) {
            Response response = client.preparePost(plainRedirect())
                    .setHeader(REDIRECT_TO, otherOriginTarget())
                    .setHeader(REDIRECT_STATUS, "302")
                    .setBody("form=value")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertEquals("GET", methodOnTarget.get());
            assertEquals("", bodyOnTarget.get(), "the POST body is dropped by the 302 rewrite, not replayed");
        }
    }

    @Test
    public void sameOriginKeepBodyHopIsFollowedUnderTheStrictPolicy() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(
                policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY))) {
            Response response = client.preparePut(plainRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setHeader(REDIRECT_STATUS, "308")
                    .setBody("sensitive-content")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertEquals("sensitive-content", bodyOnTarget.get());
        }
    }

    /**
     * RFC 3986 section 3.2.2 makes a host case-insensitive, so this is one origin and the hop must be followed.
     * {@code Uri.isSameBase} compares hosts exactly, which is why the arm has its own predicate.
     */
    @Test
    public void crossOriginArmTreatsAHostDifferingOnlyInCaseAsSameOrigin() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(
                policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY))) {
            Response response = client.preparePut(plainRedirect())
                    .setHeader(REDIRECT_TO, "http://LOCALHOST:" + port1 + "/target")
                    .setHeader(REDIRECT_STATUS, "308")
                    .setBody("sensitive-content")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertEquals("sensitive-content", bodyOnTarget.get(),
                    "a host differing only in case is the same origin");
        }
    }

    @Test
    public void sameHostHttpToHttpsUpgradeWithContentIsRefusedUnderTheStrictPolicy() throws Exception {
        // RFC 9110 section 4.2.2: http and https are distinct origins. Documented and deliberate - the remedy
        // is to address https directly, and this request has already gone out in the clear once.
        try (AsyncHttpClient client = asyncHttpClient(
                policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY))) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client.preparePut(plainRedirect())
                    .setHeader(REDIRECT_TO, secureTarget())
                    .setHeader(REDIRECT_STATUS, "308")
                    .setBody("sensitive-content")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertRefused(e);
            assertFalse(targetHit.get());
        }
    }

    /**
     * Only the request content is restricted. A bodiless cross-origin hop still forwards caller-set headers
     * other than the credentials, which the constant's javadoc says explicitly so nobody reads the arm as
     * "cross-origin redirects are now safe".
     */
    @Test
    public void strictPolicyStillForwardsACustomHeaderOnABodylessCrossOriginHop() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(
                policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY))) {
            client.prepareGet(plainRedirect())
                    .setHeader(REDIRECT_TO, otherOriginTarget())
                    .setHeader("X-Api-Key", "still-forwarded")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals("still-forwarded", customHeaderOnTarget.get());
        }
    }

    /**
     * The policy is subtractive: allowing a hop must not re-authorize a credential that is stripped today.
     */
    @Test
    public void strictPolicyDoesNotRelaxCredentialStripping() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(
                policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY))) {
            client.prepareGet(plainRedirect())
                    .setHeader(REDIRECT_TO, otherOriginTarget())
                    .setHeader("Authorization", "Bearer secret-token")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertTrue(targetHit.get());
            assertNull(authOnTarget.get(), "Authorization must still be stripped cross-origin");
        }
    }

    // ---------------------------------------------------------------- status code on the exception

    @Test
    public void refusedSeeOtherReportsItsStatusCode() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client.preparePost(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setHeader(REDIRECT_STATUS, "303")
                    .setBody("already-applied")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertEquals(303, assertRefused(e).getStatusCode(),
                    "a 303 tells the caller the origin has very likely already applied the request");
        }
    }

    @Test
    public void refusedTemporaryRedirectReportsItsStatusCode() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client.preparePut(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setHeader(REDIRECT_STATUS, "307")
                    .setBody("not-applied")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            RedirectRefusedException refused = assertRefused(e);
            assertEquals(307, refused.getStatusCode());
            assertNotNull(refused.getTargetUri());
            assertEquals("http", refused.getTargetUri().getScheme());
        }
    }

    // ---------------------------------------------------------------- budget, channel, message

    /**
     * The gate sits below the redirect-budget check, so a hop that is both over budget and refusable is
     * reported as a budget failure. Stated in the enum javadoc so a caller does not read a budget error as
     * "no security refusal happened".
     */
    @Test
    public void overBudgetRefusableHopStillThrowsMaxRedirectException() throws Exception {
        AsyncHttpClientConfig cfg = policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE).setMaxRedirects(1).build();
        try (AsyncHttpClient client = asyncHttpClient(cfg)) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertInstanceOf(MaxRedirectException.class, e.getCause(),
                    "the redirect budget is checked before the policy; got " + e.getCause());
        }
    }

    @Test
    public void refusedDowngradeDoesNotPoolTheConnection() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            assertThrows(ExecutionException.class, () -> client.preparePut(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setHeader(REDIRECT_STATUS, "308")
                    .setBody("sensitive-content")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, client.getClientStats().getTotalIdleConnectionCount(),
                    "the refusal closes the channel rather than offering it to the pool");
        }
    }

    /**
     * A chunked request whose body may still be in flight when the redirect arrives: the socket's framing is
     * indeterminate, so it must not go back to the pool. The throw routes through the handler's failure path,
     * which closes the channel rather than offering it.
     */
    @Test
    public void refusedDowngradeOnAChunkedPostDoesNotPoolTheConnection() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            byte[] payload = new byte[64 * 1024];
            assertThrows(ExecutionException.class, () -> client.preparePost(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setHeader(REDIRECT_STATUS, "307")
                    // An unknown-length generator, so the request is sent with Transfer-Encoding: chunked.
                    .setBody(new org.asynchttpclient.request.body.generator.InputStreamBodyGenerator(
                            new java.io.ByteArrayInputStream(payload)))
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));

            assertEquals(0, client.getClientStats().getTotalIdleConnectionCount(),
                    "a chunked request's socket must not be pooled after a refusal");
            assertFalse(targetHit.get());
        }
    }

    /**
     * With Expect: 100-continue the body is deliberately withheld until the origin invites it, so a refusal can
     * land while the peer is still expecting content this client announced.
     */
    @Test
    public void refusedDowngradeOnAnExpectContinuePostDoesNotPoolTheConnection() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            assertThrows(ExecutionException.class, () -> client.preparePost(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setHeader(REDIRECT_STATUS, "307")
                    .setHeader("Expect", "100-continue")
                    .setBody("deferred-content")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));

            assertEquals(0, client.getClientStats().getTotalIdleConnectionCount(),
                    "a deferred-body request's socket must not be pooled after a refusal");
            assertFalse(targetHit.get());
        }
    }

    /**
     * The refusal message names both origins by base URL only. Neither the caller's query string - where a
     * presigned credential lives - nor the server-chosen target path may appear in it.
     */
    @Test
    public void refusalMessageCarriesNoPathQueryOrUserInfo() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client
                    .preparePut("https://user:pw@localhost:" + port2 + "/redirect?token=CALLER_SECRET")
                    .setHeader(REDIRECT_TO, plainTarget() + "?leak=TARGET_SECRET")
                    .setHeader(REDIRECT_STATUS, "308")
                    .setBody("sensitive-content")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));

            String message = assertRefused(e).getMessage();
            assertFalse(message.contains("CALLER_SECRET"), "the caller's query string must not be logged: " + message);
            assertFalse(message.contains("TARGET_SECRET"), "the target's query string must not be logged: " + message);
            assertFalse(message.contains("user:pw"), "userinfo must not be logged: " + message);
            assertTrue(message.contains("https://localhost:" + port2), message);
            assertTrue(message.contains("http://localhost:" + port1), message);
            assertTrue(message.contains("308"), "the refused status belongs in the message: " + message);
        }
    }

    // ---------------------------------------------------------------- per-request override

    @Test
    public void perRequestPolicyTightensTheClientPolicy() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.ALLOW_ALL))) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setRedirectPolicy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE)
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertRefused(e);
            assertFalse(targetHit.get());
        }
    }

    @Test
    public void perRequestPolicyCannotWeakenTheClientPolicy() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setRedirectPolicy(RedirectPolicy.ALLOW_ALL)
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertRefused(e);
            assertFalse(targetHit.get(), "an override must never re-enable a hop the client refuses");
        }
    }

    /**
     * The restrictions are resolved once per exchange, so they are still in force at hop 2 even though the
     * interceptor rebuilds the request for each hop.
     */
    @Test
    public void perRequestPolicyStillAppliesAtALaterHop() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.ALLOW_ALL))) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client
                    .preparePut("http://localhost:" + port1 + "/hop1")
                    .setBody("sensitive-content")
                    .setRedirectPolicy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY)
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertRefused(e);
            assertFalse(targetHit.get(), "the cross-origin hop 2 must be refused");
        }
    }

    /**
     * The client-wide arm also survives the multi-hop rebuild, including the {@code !keepBody} branch that
     * builds a fresh RequestBuilder copying only the fields it names.
     */
    @Test
    public void clientPolicyStillAppliesAtALaterHop() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(
                policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY))) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client
                    .preparePut("http://localhost:" + port1 + "/hop1")
                    .setBody("sensitive-content")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertRefused(e);
            assertFalse(targetHit.get());
        }
    }

    /**
     * A RequestFilter runs BEFORE the exchange exists, so it is the one place a per-request override can be
     * lost: a filter that builds a fresh request from the URL discards every per-request field - the policy
     * exactly as it discards followRedirect and the timeouts. What must survive is the CLIENT-wide arm, which
     * is read from the configuration and so cannot be dropped by any request rebuild.
     */
    @Test
    public void aFilterRebuildingFromScratchLosesThePerRequestPolicyButNotTheClientPolicy() throws Exception {
        org.asynchttpclient.filter.RequestFilter rebuildFromScratch = new org.asynchttpclient.filter.RequestFilter() {
            @Override
            public <T> org.asynchttpclient.filter.FilterContext<T> filter(
                    org.asynchttpclient.filter.FilterContext<T> ctx) {
                org.asynchttpclient.Request rebuilt = new RequestBuilder(ctx.getRequest().getMethod())
                        .setUrl(ctx.getRequest().getUri().toUrl())
                        .setHeaders(ctx.getRequest().getHeaders())
                        .build();
                return new org.asynchttpclient.filter.FilterContext.FilterContextBuilder<>(ctx)
                        .request(rebuilt)
                        .build();
            }
        };

        // Client permits, request tightens, filter rebuilds: the tightening is lost with the rest of the
        // per-request state. Pinned so the limitation is explicit rather than discovered.
        AsyncHttpClientConfig permissive = policy(RedirectPolicy.ALLOW_ALL)
                .addRequestFilter(rebuildFromScratch).build();
        try (AsyncHttpClient client = asyncHttpClient(permissive)) {
            Response response = client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setRedirectPolicy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE)
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertTrue(targetHit.get(), "a from-scratch rebuild drops per-request state, this one included");
        }

        // The client-wide arm is the security floor and no request rebuild can reach it.
        targetHit.set(false);
        AsyncHttpClientConfig strict = policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE)
                .addRequestFilter(rebuildFromScratch).build();
        try (AsyncHttpClient client = asyncHttpClient(strict)) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertRefused(e);
            assertFalse(targetHit.get(), "the client-wide arm must survive any request rebuild");
        }
    }

    /**
     * The supported way for a filter to keep the caller's request intact. RequestBuilderBase copies the policy
     * from its prototype, so toBuilder() preserves the override where a fresh builder does not.
     */
    @Test
    public void perRequestPolicySurvivesAFilterThatUsesToBuilder() throws Exception {
        AsyncHttpClientConfig cfg = policy(RedirectPolicy.ALLOW_ALL)
                .addRequestFilter(new org.asynchttpclient.filter.RequestFilter() {
                    @Override
                    public <T> org.asynchttpclient.filter.FilterContext<T> filter(
                            org.asynchttpclient.filter.FilterContext<T> ctx) {
                        org.asynchttpclient.Request rebuilt = ctx.getRequest().toBuilder()
                                .addHeader("X-Api-Key", "added-by-filter")
                                .build();
                        return new org.asynchttpclient.filter.FilterContext.FilterContextBuilder<>(ctx)
                                .request(rebuilt)
                                .build();
                    }
                })
                .build();

        try (AsyncHttpClient client = asyncHttpClient(cfg)) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setRedirectPolicy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE)
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertRefused(e);
            assertFalse(targetHit.get());
        }
    }

    // ---------------------------------------------------------------- filters and shipped handlers

    /**
     * The refusal must not pass through the IOExceptionFilter chain. If it did, one refusal could become up to
     * maxRequestRetry further submissions of content the caller asked to send once - which is why
     * RedirectRefusedException deliberately does not extend IOException.
     */
    @Test
    public void refusedRedirectIsNotReplayedByIoExceptionFilters() throws Exception {
        AtomicBoolean filterConsulted = new AtomicBoolean();
        AsyncHttpClientConfig cfg = policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE)
                .setMaxRequestRetry(5)
                .addIOExceptionFilter(new org.asynchttpclient.filter.IOExceptionFilter() {
                    @Override
                    public <T> org.asynchttpclient.filter.FilterContext<T> filter(
                            org.asynchttpclient.filter.FilterContext<T> ctx) {
                        filterConsulted.set(true);
                        return new org.asynchttpclient.filter.FilterContext.FilterContextBuilder<>(ctx)
                                .replayRequest(true)
                                .build();
                    }
                })
                .build();

        try (AsyncHttpClient client = asyncHttpClient(cfg)) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> client.preparePut(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setHeader(REDIRECT_STATUS, "308")
                    .setBody("send-me-once")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));

            Throwable cause = assertRefused(e);
            // Pinned separately from the behaviour above: this assertion survives a harness rewrite that the
            // "filter was not consulted" half might not.
            assertFalse(cause instanceof IOException,
                    "RedirectRefusedException must not be an IOException, or the replay chain reopens");
            assertFalse(filterConsulted.get(), "an IOExceptionFilter must not see a policy refusal");
            assertFalse(targetHit.get());
        }
    }

    /**
     * Semaphore leaks are a recurring theme here and the refusal is a new termination path, so pin that the
     * throttle permit comes back: a second request must be able to acquire it.
     */
    @Test
    public void refusedRedirectReleasesThrottlePermit() throws Exception {
        AsyncHttpClientConfig cfg = policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE)
                .addRequestFilter(new org.asynchttpclient.filter.ThrottleRequestFilter(1))
                .build();

        try (AsyncHttpClient client = asyncHttpClient(cfg)) {
            assertThrows(ExecutionException.class, () -> client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));

            // If the permit leaked, this blocks until the filter's timeout and then fails.
            Response second = client.prepareGet(plainTarget()).execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, second.getStatusCode(), "the throttle permit must have been released");
        }
    }

    /**
     * BodyDeferringAsyncHandler writes every body byte to the caller's stream with no status check, and its
     * onCompleted closes that stream. Delivering a refused 3xx would therefore write the redirect's entity into
     * the caller's download file; failing the exchange takes the onThrowable path instead.
     */
    @Test
    public void refusedRedirectWritesNothingToABodyDeferringOutputStream() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
            org.asynchttpclient.handler.BodyDeferringAsyncHandler handler =
                    new org.asynchttpclient.handler.BodyDeferringAsyncHandler(sink);

            assertThrows(ExecutionException.class, () -> client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .execute(handler).get(TIMEOUT, TimeUnit.SECONDS));

            assertEquals(0, sink.size(),
                    "the refused 3xx entity must not be written to the caller's stream, got: " + sink);
        }
    }

    /**
     * ResumableAsyncHandler aborts a non-200/206 status but its onCompleted still calls
     * onAllBytesReceived(), so a delivered 3xx would tell a resumable download it finished.
     */
    @Test
    public void refusedRedirectDoesNotSignalAllBytesReceivedToAResumableListener() throws Exception {
        AtomicBoolean allBytesReceived = new AtomicBoolean();
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            org.asynchttpclient.handler.resumable.ResumableAsyncHandler handler =
                    new org.asynchttpclient.handler.resumable.ResumableAsyncHandler()
                            .setResumableListener(new org.asynchttpclient.handler.resumable.ResumableListener() {
                                @Override
                                public void onBytesReceived(java.nio.ByteBuffer byteBuffer) {
                                }

                                @Override
                                public void onAllBytesReceived() {
                                    allBytesReceived.set(true);
                                }

                                @Override
                                public long length() {
                                    return 0;
                                }
                            });

            assertThrows(ExecutionException.class, () -> client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .execute(handler).get(TIMEOUT, TimeUnit.SECONDS));

            assertFalse(allBytesReceived.get(),
                    "a refused redirect must not report a completed download");
        }
    }
}
