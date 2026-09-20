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

import io.netty.buffer.Unpooled;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.handler.MaxRedirectException;
import org.asynchttpclient.handler.RedirectRefusedException;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.request.body.generator.FileBodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.request.body.multipart.StringPart;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The redirect refusal gate in {@code Redirect30xInterceptor}.
 */
public class RedirectRefusalTest extends AbstractBasicTest {

    private final AtomicBoolean targetHit = new AtomicBoolean();
    private final AtomicBoolean retried = new AtomicBoolean();
    private final AtomicReference<String> methodOnTarget = new AtomicReference<>();
    private final AtomicReference<String> bodyOnTarget = new AtomicReference<>();

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
    }

    @BeforeEach
    public void resetCaptures() {
        targetHit.set(false);
        retried.set(false);
        methodOnTarget.set(null);
        bodyOnTarget.set(null);
    }

    // ---------------------------------------------------------------- scheme downgrade

    @Test
    public void downgradeIsFollowedByDefault() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig())) {
            Response response = client.prepareGet(secure("/downgrade")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertTrue(targetHit.get());
        }
    }

    @Test
    public void downgradeOnBodylessGetIsRefused() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseSchemeDowngradeOnRedirect(true))) {
            RedirectRefusedException refusal = expectRefusal(client.prepareGet(secure("/downgrade")));
            assertEquals(307, refusal.getStatusCode());
            assertEquals(RedirectRefusedException.Reason.SCHEME_DOWNGRADE, refusal.getReason());
            assertEquals("https", requireNonNull(refusal.getSourceUri()).getScheme());
            assertEquals("http", requireNonNull(refusal.getTargetUri()).getScheme());
            assertFalse(targetHit.get(), "the refused target must not be reached");
        }
    }

    @Test
    public void downgradeOnPutWithBodyIsRefused() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseSchemeDowngradeOnRedirect(true))) {
            expectRefusal(client.preparePut(secure("/downgrade")).setBody("payload"));
            assertFalse(targetHit.get());
        }
    }

    @Test
    public void theDowngradeArmNeverRefusesAnUpgrade() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseSchemeDowngradeOnRedirect(true))) {
            Response response = client.preparePut(plain("/upgrade")).setBody("payload")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertEquals("payload", bodyOnTarget.get());
        }
    }

    /**
     * The exempted upgrade keeps its port, so the hop leaves the gate and the client goes on to attempt TLS
     * against the plaintext connector, which fails. That failure is the point: it can only be reached by not
     * refusing. Nothing here proves the content arrives, which would need a listener on the same port.
     */
    @Test
    public void anUpgradeThatKeepsItsPortIsNotRefused() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseCrossOriginBodyOnRedirect(true))) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> client.preparePut(plain("/upgrade-same-port")).setBody("payload")
                            .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause(),
                    "expected the TLS attempt against the plaintext connector to fail, got: " + failure.getCause());
            assertFalse(failure.getCause() instanceof RedirectRefusedException,
                    "the same-host upgrade should have been exempt, but was refused: " + failure.getCause());
        }
    }

    // The two connectors are on unrelated ephemeral ports, so this is a port change, not an upgrade.
    @Test
    public void anUpgradeThatAlsoChangesThePortIsStillRefused() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseCrossOriginBodyOnRedirect(true))) {
            expectRefusal(client.preparePut(plain("/upgrade")).setBody("payload"));
            assertFalse(targetHit.get());
        }
    }

    @Test
    public void refusalDeliversNoResponseToTheHandler() throws Exception {
        AtomicReference<Response> completed = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseSchemeDowngradeOnRedirect(true))) {
            assertThrows(ExecutionException.class, () -> client.prepareGet(secure("/downgrade"))
                    .execute(new AsyncCompletionHandler<Response>() {
                        @Override
                        public Response onCompleted(Response response) {
                            completed.set(response);
                            return response;
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                            failed.set(t);
                        }
                    }).get(TIMEOUT, TimeUnit.SECONDS));

            assertNull(completed.get(), "a refused hop must not hand the 3xx back as the result");
            assertInstanceOf(RedirectRefusedException.class, failed.get());
        }
    }

    // ---------------------------------------------------------------- cross-origin body

    @Test
    public void crossOriginBodyReplayIsRefused() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseCrossOriginBodyOnRedirect(true))) {
            RedirectRefusedException refusal = expectRefusal(client.preparePut(plain("/cross-origin")).setBody("payload"));
            assertEquals(307, refusal.getStatusCode());
            assertEquals(RedirectRefusedException.Reason.CROSS_ORIGIN_BODY, refusal.getReason());
            assertFalse(targetHit.get(), "the content must not reach the other origin");
        }
    }

    /**
     * 301 and 302 keep method and content here, because the legacy rewrite is limited to POST.
     */
    @ParameterizedTest
    @ValueSource(ints = {301, 302, 308})
    public void crossOriginBodyReplayIsRefusedOnEveryBodyPreservingStatus(int statusCode) throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseCrossOriginBodyOnRedirect(true))) {
            RedirectRefusedException refusal =
                    expectRefusal(client.preparePut(plain("/cross-origin-" + statusCode)).setBody("payload"));
            assertEquals(statusCode, refusal.getStatusCode());
            assertFalse(targetHit.get());
        }
    }

    /**
     * The gate reads the request rather than the wire, so each body representation has to be recognised.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("bodyRepresentations")
    public void crossOriginBodyReplayIsRefusedForEveryRepresentation(String name, Consumer<BoundRequestBuilder> body)
            throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseCrossOriginBodyOnRedirect(true))) {
            BoundRequestBuilder request = client.preparePut(plain("/cross-origin"));
            body.accept(request);
            expectRefusal(request);
            assertFalse(targetHit.get(), name + " reached the other origin");
        }
    }

    /**
     * Getting the refusal rather than ensureBodyReplayable's IOException is what proves the gate runs first.
     */
    @Test
    public void aConsumedStreamIsRefusedRatherThanFailingReplayable() throws Exception {
        AtomicInteger filterCalls = new AtomicInteger();
        DefaultAsyncHttpClientConfig.Builder builder = followingConfig()
                .setRefuseCrossOriginBodyOnRedirect(true)
                .addIOExceptionFilter(new IOExceptionFilter() {
                    @Override
                    public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                        filterCalls.incrementAndGet();
                        return ctx;
                    }
                });

        try (AsyncHttpClient client = asyncHttpClient(builder)) {
            expectRefusal(client.preparePut(plain("/cross-origin")).setBody(nonResettable("payload")));
            assertEquals(0, filterCalls.get());
            assertFalse(targetHit.get());
        }
    }

    private static Stream<Arguments> bodyRepresentations() throws IOException {
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        File file = File.createTempFile("ahc-redirect-refusal", ".txt");
        file.deleteOnExit();
        Files.write(file.toPath(), payload);

        return Stream.of(
                Arguments.of("byteData", (Consumer<BoundRequestBuilder>) rb -> rb.setBody(payload)),
                Arguments.of("stringData", (Consumer<BoundRequestBuilder>) rb -> rb.setBody("payload")),
                Arguments.of("byteBufferData", (Consumer<BoundRequestBuilder>) rb -> rb.setBody(ByteBuffer.wrap(payload))),
                Arguments.of("byteBufData", (Consumer<BoundRequestBuilder>) rb -> rb.setBody(Unpooled.wrappedBuffer(payload))),
                Arguments.of("streamData", (Consumer<BoundRequestBuilder>) rb -> rb.setBody(new ByteArrayInputStream(payload))),
                Arguments.of("file", (Consumer<BoundRequestBuilder>) rb -> rb.setBody(file)),
                Arguments.of("formParams", (Consumer<BoundRequestBuilder>) rb -> rb.setFormParams(singletonList(new Param("k", "v")))),
                Arguments.of("bodyParts", (Consumer<BoundRequestBuilder>) rb -> rb.addBodyPart(new StringPart("k", "v"))),
                Arguments.of("compositeByteData", (Consumer<BoundRequestBuilder>) rb ->
                        rb.setBody(singletonList(payload))),
                Arguments.of("fileBodyGenerator", (Consumer<BoundRequestBuilder>) rb ->
                        rb.setBody(new FileBodyGenerator(file))),
                Arguments.of("bodyGenerator", (Consumer<BoundRequestBuilder>) rb ->
                        rb.setBody(new InputStreamBodyGenerator(new ByteArrayInputStream(payload)))));
    }

    private static InputStream nonResettable(String content) {
        return new FilterInputStream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            @Override
            public boolean markSupported() {
                return false;
            }
        };
    }

    /**
     * keepBody is true for every GET on a 301/302/307/308, so a gate that keyed on it alone would refuse most
     * of the redirects on the web.
     */
    @Test
    public void crossOriginBodylessGetIsFollowed() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseCrossOriginBodyOnRedirect(true))) {
            Response response = client.prepareGet(plain("/cross-origin")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertTrue(targetHit.get());
        }
    }

    @Test
    public void crossOriginPostRewrittenToGetIsFollowed() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(strictConfig())) {
            Response response = client.preparePost(plain("/see-other")).setBody("payload")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertEquals("GET", methodOnTarget.get());
            assertEquals("", bodyOnTarget.get());
        }
    }

    @Test
    public void sameOriginBodyHopIsFollowed() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(strictConfig())) {
            Response response = client.preparePut(plain("/same-origin")).setBody("payload")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertEquals("payload", bodyOnTarget.get());
        }
    }

    @Test
    public void theTwoArmsAreIndependent() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseSchemeDowngradeOnRedirect(true))) {
            Response response = client.preparePut(plain("/cross-origin")).setBody("payload")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode(), "the downgrade arm must not refuse a cross-origin body");
        }
    }

    // ---------------------------------------------------------------- how the refusal terminates

    /**
     * A refusal that was an IOException would come back as further attempts to send the content.
     */
    @Test
    public void refusalIsNotAnIOExceptionAndIsNotReplayed() throws Exception {
        AtomicInteger filterCalls = new AtomicInteger();
        DefaultAsyncHttpClientConfig.Builder builder = followingConfig()
                .setRefuseSchemeDowngradeOnRedirect(true)
                .addIOExceptionFilter(new IOExceptionFilter() {
                    @Override
                    public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                        filterCalls.incrementAndGet();
                        return ctx;
                    }
                });

        try (AsyncHttpClient client = asyncHttpClient(builder)) {
            Throwable cause = expectRefusal(client.prepareGet(secure("/downgrade")));
            assertFalse(cause instanceof IOException);
            assertEquals(0, filterCalls.get(), "a refusal must not enter the IOExceptionFilter chain");
        }
    }

    @Test
    public void theRedirectBudgetIsCheckedFirst() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig()
                .setMaxRedirects(1)
                .setRefuseSchemeDowngradeOnRedirect(true))) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> client.prepareGet(secure("/downgrade")).execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertInstanceOf(MaxRedirectException.class, failure.getCause());
        }
    }

    @Test
    public void theMessageCarriesNeitherPathQueryNorUserInfo() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseSchemeDowngradeOnRedirect(true))) {
            RedirectRefusedException refusal = expectRefusal(
                    client.prepareGet("https://user:pw@localhost:" + port2 + "/downgrade?token=CALLER_SECRET"));

            String message = refusal.getMessage();
            assertFalse(message.contains("CALLER_SECRET"), message);
            assertFalse(message.contains("SERVER_SECRET"), message);
            assertFalse(message.contains("user:pw"), message);
            assertTrue(message.contains("https://localhost:" + port2), message);
            assertTrue(message.contains("http://127.0.0.1:" + port1), message);
        }
    }

    @Test
    public void theMessageIsBuiltFromBaseUrlsWhoeverConstructsIt() {
        Uri source = Uri.create("https://user:pw@localhost:8443/secret/path?token=CALLER_SECRET");
        Uri target = Uri.create("http://admin:hunter2@127.0.0.1:8080/other?token=SERVER_SECRET");

        String message = new RedirectRefusedException(
                RedirectRefusedException.Reason.SCHEME_DOWNGRADE, 307, source, target).getMessage();

        assertFalse(message.contains("CALLER_SECRET"), message);
        assertFalse(message.contains("SERVER_SECRET"), message);
        assertFalse(message.contains("user:pw"), message);
        assertFalse(message.contains("hunter2"), message);
        assertFalse(message.contains("/secret/path"), message);
        assertTrue(message.contains("https://localhost:8443"), message);
        assertTrue(message.contains("http://127.0.0.1:8080"), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/no-location", "/empty-location"})
    public void aRedirectWithNoUsableLocationIsDeliveredAsAResponse(String path) throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig())) {
            Response response = client.prepareGet(plain(path)).execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(302, response.getStatusCode());
        }
    }

    // ---------------------------------------------------------------- per-request override

    @Test
    public void aRequestCanRefuseWhereTheClientAllows() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig())) {
            expectRefusal(client.prepareGet(secure("/downgrade")).setRefuseSchemeDowngradeOnRedirect(true));
            assertFalse(targetHit.get());
        }
    }

    @Test
    public void aRequestCannotAllowWhereTheClientRefuses() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseSchemeDowngradeOnRedirect(true))) {
            expectRefusal(client.prepareGet(secure("/downgrade")).setRefuseSchemeDowngradeOnRedirect(false));
            assertFalse(targetHit.get(), "a request must not be able to relax the client's posture");
        }
    }

    @Test
    public void anOverrideSurvivesAHopThatKeepsTheBody() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig())) {
            expectRefusal(client.preparePut(plain("/hop1")).setBody("payload")
                    .setRefuseCrossOriginBodyOnRedirect(true));
            assertFalse(targetHit.get(), "the override must still hold on the second hop");
        }
    }

    @Test
    public void aRequestCannotAllowTheCrossOriginBodyHopWhereTheClientRefuses() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig().setRefuseCrossOriginBodyOnRedirect(true))) {
            expectRefusal(client.preparePut(plain("/cross-origin")).setBody("payload")
                    .setRefuseCrossOriginBodyOnRedirect(false));
            assertFalse(targetHit.get(), "a request must not be able to relax the client's posture");
        }
    }

    @Test
    public void aFilterThatRebuildsTheRequestCannotRelaxTheOverride() throws Exception {
        DefaultAsyncHttpClientConfig.Builder builder = followingConfig()
                .addResponseFilter(new ResponseFilter() {
                    @Override
                    public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                        if (ctx.getResponseStatus() != null && ctx.getResponseStatus().getStatusCode() == 503) {
                            return new FilterContext.FilterContextBuilder<>(ctx)
                                    .request(new RequestBuilder("PUT").setUrl(plain("/cross-origin"))
                                            .setBody("payload").build())
                                    .replayRequest(true)
                                    .build();
                        }
                        return ctx;
                    }
                });

        try (AsyncHttpClient client = asyncHttpClient(builder)) {
            expectRefusal(client.preparePut(plain("/retry-once")).setBody("payload")
                    .setRefuseCrossOriginBodyOnRedirect(true));
            assertFalse(targetHit.get(), "a rebuilt request must not drop the caller's refusal");
        }
    }

    /**
     * The mirror of the test above. The fold is one-way, not a copy, so a rebuilt request can still add a
     * refusal the caller never asked for.
     */
    @Test
    public void aFilterThatRebuildsTheRequestCanStillTightenTheOverride() throws Exception {
        DefaultAsyncHttpClientConfig.Builder builder = followingConfig()
                .addResponseFilter(new ResponseFilter() {
                    @Override
                    public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                        if (ctx.getResponseStatus() != null && ctx.getResponseStatus().getStatusCode() == 503) {
                            return new FilterContext.FilterContextBuilder<>(ctx)
                                    .request(new RequestBuilder("PUT").setUrl(plain("/cross-origin"))
                                            .setBody("payload")
                                            .setRefuseCrossOriginBodyOnRedirect(true).build())
                                    .replayRequest(true)
                                    .build();
                        }
                        return ctx;
                    }
                });

        try (AsyncHttpClient client = asyncHttpClient(builder)) {
            expectRefusal(client.preparePut(plain("/retry-once")).setBody("payload"));
            assertFalse(targetHit.get(), "a refusal set on the rebuilt request must reach the exchange");
        }
    }

    /**
     * A 303 rebuilds from an empty builder, the only branch where the override is copied across by hand.
     * Both client arms are off, so a refusal can only come from the override surviving that rebuild.
     */
    @Test
    public void anOverrideSurvivesTheRebuildOnABodylessHop() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(followingConfig())) {
            expectRefusal(client.preparePost(secure("/see-other-then-downgrade")).setBody("payload")
                    .setRefuseSchemeDowngradeOnRedirect(true));
            assertFalse(targetHit.get());
        }
    }

    // ---------------------------------------------------------------- fixture

    private DefaultAsyncHttpClientConfig.Builder followingConfig() {
        return config().setFollowRedirect(true).setUseInsecureTrustManager(true).setMaxRedirects(5);
    }

    private DefaultAsyncHttpClientConfig.Builder strictConfig() {
        return followingConfig().setRefuseSchemeDowngradeOnRedirect(true).setRefuseCrossOriginBodyOnRedirect(true);
    }

    private String plain(String path) {
        return "http://localhost:" + port1 + path;
    }

    private String secure(String path) {
        return "https://localhost:" + port2 + path;
    }

    private static RedirectRefusedException expectRefusal(BoundRequestBuilder request) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> request.execute().get(TIMEOUT, TimeUnit.SECONDS));
        return assertInstanceOf(RedirectRefusedException.class, failure.getCause());
    }

    private class RedirectHandler extends AbstractHandler {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException {
            // Drain first: answering mid-write makes the write fail before the refusal surfaces, which makes
            // the reported cause racy even though the refusal itself still holds.
            String body = drain(request);
            baseRequest.setHandled(true);

            // localhost and 127.0.0.1 are different origins to the gate but the same server here, which is how
            // a cross-origin hop is staged without a second listener.
            String path = stripPrefix(target);
            switch (path) {
                case "/downgrade":
                    redirect(response, 307, "http://127.0.0.1:" + port1 + "/target?token=SERVER_SECRET");
                    return;
                case "/upgrade":
                    redirect(response, 302, "https://localhost:" + port2 + "/target");
                    return;
                case "/upgrade-same-port":
                    redirect(response, 307, "https://localhost:" + port1 + "/target");
                    return;
                case "/cross-origin-301":
                    redirect(response, 301, "http://127.0.0.1:" + port1 + "/target");
                    return;
                case "/cross-origin-302":
                    redirect(response, 302, "http://127.0.0.1:" + port1 + "/target");
                    return;
                case "/cross-origin-308":
                    redirect(response, 308, "http://127.0.0.1:" + port1 + "/target");
                    return;
                case "/retry-once":
                    response.setStatus(retried.compareAndSet(false, true) ? 503 : 200);
                    return;
                case "/no-location":
                    response.setStatus(302);
                    return;
                case "/empty-location":
                    response.setStatus(302);
                    response.setHeader("Location", "");
                    return;
                case "/cross-origin":
                    redirect(response, 307, "http://127.0.0.1:" + port1 + "/target");
                    return;
                case "/same-origin":
                    redirect(response, 307, "http://localhost:" + port1 + "/target");
                    return;
                case "/see-other":
                    redirect(response, 303, "http://127.0.0.1:" + port1 + "/target");
                    return;
                case "/see-other-then-downgrade":
                    redirect(response, 303, "https://localhost:" + port2 + "/downgrade");
                    return;
                case "/hop1":
                    redirect(response, 307, "http://localhost:" + port1 + "/hop2");
                    return;
                case "/hop2":
                    redirect(response, 307, "http://127.0.0.1:" + port1 + "/target");
                    return;
                default:
                    targetHit.set(true);
                    methodOnTarget.set(request.getMethod());
                    bodyOnTarget.set(body);
                    response.setStatus(200);
                    response.getOutputStream().write("target".getBytes(StandardCharsets.UTF_8));
            }
        }

        private String stripPrefix(String target) {
            int query = target.indexOf('?');
            return query < 0 ? target : target.substring(0, query);
        }

        private void redirect(HttpServletResponse response, int status, String location) throws IOException {
            response.setStatus(status);
            response.setHeader("Location", location);
            response.getOutputStream().write("moved".getBytes(StandardCharsets.UTF_8));
        }

        private String drain(HttpServletRequest request) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            try (InputStream in = request.getInputStream()) {
                for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
