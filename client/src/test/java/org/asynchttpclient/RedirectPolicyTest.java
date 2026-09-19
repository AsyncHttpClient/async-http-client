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

import io.netty.handler.codec.http.HttpHeaders;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.filter.ThrottleRequestFilter;
import org.asynchttpclient.handler.BodyDeferringAsyncHandler;
import org.asynchttpclient.handler.MaxRedirectException;
import org.asynchttpclient.handler.RedirectRefusedException;
import org.asynchttpclient.handler.resumable.ResumableAsyncHandler;
import org.asynchttpclient.handler.resumable.ResumableListener;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
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
 * The {@code setUpGlobal()} override stays on {@link BeforeAll} so one server serves the class and the
 * inherited {@code @AfterAll} stops it. {@code HttpToHttpsRedirectTest} re-annotates it {@code @BeforeEach}
 * against an {@code @AfterAll} teardown and leaks a server per test - don't copy that.
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
            // Drain first: answering mid-write makes the write fail before the refusal surfaces, which makes
            // the reported cause racy even though the refusal itself still holds.
            byte[] drained = readFully(request);
            if (target.endsWith("/hop1")) {
                // same-origin first hop, so hop 2 proves the policy survives a rebuild
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
                response.setStatus(status == null ? 302 : parseStatus(status));
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

    private static int parseStatus(String status) {
        try {
            return Integer.parseInt(status);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("test sent a non-numeric " + REDIRECT_STATUS + ": " + status, e);
        }
    }

    private static byte[] readFully(HttpServletRequest request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
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

    /** Same port, different host string: a second origin on one server, as {@code RedirectBodyTest} does. */
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

    /** No content, unsafe method: handing back the 3xx would read as success while the DELETE never happened. */
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

    /** Nothing may reach the handler as a response - that is what protects the two handlers tested below. */
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
                public State onHeadersReceived(HttpHeaders headers) {
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

    /** keepBody is true for every GET here, so gating on it alone would refuse most redirects on the web. */
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

    /** Form POST answered 302: the content is already dropped, so there is nothing to replay. */
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

    /** Host case is not part of the origin (RFC 3986 3.2.2), which is why the arm has its own predicate. */
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

    /** The arm restricts content, not headers - so nobody reads it as "cross-origin is safe now". */
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

    /** Subtractive: allowing a hop must not re-authorize a credential that is stripped today. */
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

    /** The budget is checked first, so an over-budget hop reports that even when it was also refusable. */
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

    /** The body may still be in flight, so the socket's framing is indeterminate and it must not be pooled. */
    @Test
    public void refusedDowngradeOnAChunkedPostDoesNotPoolTheConnection() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            byte[] payload = new byte[64 * 1024];
            assertThrows(ExecutionException.class, () -> client.preparePost(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .setHeader(REDIRECT_STATUS, "307")
                    // An unknown-length generator, so the request is sent with Transfer-Encoding: chunked.
                    .setBody(new InputStreamBodyGenerator(
                            new ByteArrayInputStream(payload)))
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));

            assertEquals(0, client.getClientStats().getTotalIdleConnectionCount(),
                    "a chunked request's socket must not be pooled after a refusal");
            assertFalse(targetHit.get());
        }
    }

    /** The body is withheld until invited, so a refusal lands while the peer still expects announced content. */
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

    /** Base URLs only: neither the caller's query - where a presigned credential lives - nor the target path. */
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

    /** Resolved once per exchange, so still in force at hop 2 even though each hop rebuilds the request. */
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

    /** Same for the client-wide arm, including through the !keepBody branch's fresh builder. */
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
     * A RequestFilter runs before the exchange exists, so it is the one place an override can be lost - a
     * from-scratch rebuild drops it exactly as it drops followRedirect and the timeouts. The client-wide arm
     * comes from the configuration and survives regardless.
     */
    @Test
    public void aFilterRebuildingFromScratchLosesThePerRequestPolicyButNotTheClientPolicy() throws Exception {
        RequestFilter rebuildFromScratch = new RequestFilter() {
            @Override
            public <T> FilterContext<T> filter(
                    FilterContext<T> ctx) {
                return new FilterContext.FilterContextBuilder<>(ctx)
                        .request(new RequestBuilder(ctx.getRequest().getMethod())
                                .setUrl(ctx.getRequest().getUri().toUrl())
                                .setHeaders(ctx.getRequest().getHeaders())
                                .build())
                        .build();
            }
        };

        // Client permits, request tightens, filter rebuilds: the tightening goes with the rest.
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

        // The floor, though, is out of a rebuild's reach.
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

    /** toBuilder() copies the policy from its prototype, which is how a filter keeps it. */
    @Test
    public void perRequestPolicySurvivesAFilterThatUsesToBuilder() throws Exception {
        AsyncHttpClientConfig cfg = policy(RedirectPolicy.ALLOW_ALL)
                .addRequestFilter(new RequestFilter() {
                    @Override
                    public <T> FilterContext<T> filter(
                            FilterContext<T> ctx) {
                        return new FilterContext.FilterContextBuilder<>(ctx)
                                .request(ctx.getRequest().toBuilder()
                                        .addHeader("X-Api-Key", "added-by-filter")
                                        .build())
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

    /** One refusal must not become maxRequestRetry more submissions of content meant to be sent once. */
    @Test
    public void refusedRedirectIsNotReplayedByIoExceptionFilters() throws Exception {
        AtomicBoolean filterConsulted = new AtomicBoolean();
        AsyncHttpClientConfig cfg = policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE)
                .setMaxRequestRetry(5)
                .addIOExceptionFilter(new IOExceptionFilter() {
                    @Override
                    public <T> FilterContext<T> filter(
                            FilterContext<T> ctx) {
                        filterConsulted.set(true);
                        return new FilterContext.FilterContextBuilder<>(ctx)
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
            // Pinned separately: this survives a harness rewrite that the filter-not-consulted half may not.
            assertFalse(cause instanceof IOException,
                    "RedirectRefusedException must not be an IOException, or the replay chain reopens");
            assertFalse(filterConsulted.get(), "an IOExceptionFilter must not see a policy refusal");
            assertFalse(targetHit.get());
        }
    }

    /** A new termination path, and semaphore leaks are a recurring theme here - so check the permit returns. */
    @Test
    public void refusedRedirectReleasesThrottlePermit() throws Exception {
        AsyncHttpClientConfig cfg = policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE)
                .addRequestFilter(new ThrottleRequestFilter(1))
                .build();

        try (AsyncHttpClient client = asyncHttpClient(cfg)) {
            assertThrows(ExecutionException.class, () -> client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .execute().get(TIMEOUT, TimeUnit.SECONDS));

            // Blocks until the filter's timeout and fails if the permit leaked.
            Response second = client.prepareGet(plainTarget()).execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, second.getStatusCode(), "the throttle permit must have been released");
        }
    }

    /**
     * This handler writes every body byte to the caller's stream without checking the status, so a delivered
     * 3xx would land the redirect's entity in someone's download file. Failing takes the onThrowable path.
     */
    @Test
    public void refusedRedirectWritesNothingToABodyDeferringOutputStream() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            BodyDeferringAsyncHandler handler =
                    new BodyDeferringAsyncHandler(sink);

            assertThrows(ExecutionException.class, () -> client.prepareGet(secureRedirect())
                    .setHeader(REDIRECT_TO, plainTarget())
                    .execute(handler).get(TIMEOUT, TimeUnit.SECONDS));

            assertEquals(0, sink.size(),
                    "the refused 3xx entity must not be written to the caller's stream, got: " + sink);
        }
    }

    /** It aborts a non-200/206 status but onCompleted still fires, so a delivered 3xx would say "finished". */
    @Test
    public void refusedRedirectDoesNotSignalAllBytesReceivedToAResumableListener() throws Exception {
        AtomicBoolean allBytesReceived = new AtomicBoolean();
        try (AsyncHttpClient client = asyncHttpClient(policy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE))) {
            ResumableAsyncHandler handler =
                    new ResumableAsyncHandler()
                            .setResumableListener(new ResumableListener() {
                                @Override
                                public void onBytesReceived(ByteBuffer byteBuffer) {
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
