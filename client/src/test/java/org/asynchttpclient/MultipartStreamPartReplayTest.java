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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.request.body.multipart.InputStreamPart;
import org.asynchttpclient.request.body.multipart.Part;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.WWW_AUTHENTICATE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.ADMIN;
import static org.asynchttpclient.test.TestUtils.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A retry or replay must not send a multipart {@link InputStreamPart} again once a write of the request has started
 * on its stream, and must send the streams of parts that a filter supplied for the replay as they are, as well as a
 * request that does not send its parts.
 */
public class MultipartStreamPartReplayTest extends AbstractBasicTest {

    private static final String CLOSE_FIRST = "/close-first";
    private static final String AUTHENTICATE_FIRST = "/authenticate-first";
    private static final AtomicInteger requests = new AtomicInteger();
    private static final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void setUp() {
        requests.set(0);
        receivedBodies.clear();
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                    throws IOException {
                // Counted once the headers are in, so a request that sends nothing after them counts too.
                requests.incrementAndGet();
                if ("CONNECT".equals(request.getMethod())) {
                    // Not 200, which AHC would take for an established tunnel.
                    baseRequest.setHandled(true);
                    response.setStatus(405);
                    response.setContentLength(0);
                    return;
                }
                String body = new String(IOUtils.toByteArray(request.getInputStream()), UTF_8);
                receivedBodies.add(body);
                baseRequest.setHandled(true);
                if (target.endsWith(CLOSE_FIRST) && receivedBodies.size() == 1) {
                    // The whole request was read; AHC retries a request whose connection closes without a response.
                    baseRequest.getHttpChannel().abort(new IOException("closed without a response"));
                    return;
                }
                if (target.endsWith(AUTHENTICATE_FIRST) && request.getHeader(AUTHORIZATION.toString()) == null) {
                    // Keeps the connection open, which AHC then keeps for the authenticated retry.
                    response.setStatus(401);
                    response.setHeader(WWW_AUTHENTICATE.toString(), "Basic realm=\"test\"");
                    response.setContentLength(0);
                    return;
                }
                response.setStatus(200);
                response.setContentLength(0);
            }
        };
    }

    @Test
    public void retryFailsForAConsumedStreamPart() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client
                    .preparePost(getTargetUrl() + CLOSE_FIRST)
                    .addBodyPart(new InputStreamPart("file", resettable("payload"), "file.bin"))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS));

            assertConsumed("file", thrown);
            assertEquals(1, requests.get());
            assertTrue(receivedBodies.get(0).contains("payload"));
        }
    }

    @Test
    public void retryFailsForAConsumedStreamPartWhoseMarkIsBeforeItsStart() throws Exception {
        // A reset would go back to the start of the stream, before the skipped bytes, and send more than the
        // declared length.
        InputStream stream = resettable("unwanted prefix|payload");
        assertEquals("unwanted prefix|".length(), stream.skip("unwanted prefix|".length()));
        try (AsyncHttpClient client = asyncHttpClient()) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client
                    .preparePost(getTargetUrl() + CLOSE_FIRST)
                    .addBodyPart(new InputStreamPart("file", stream, "file.bin", "payload".length()))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS));

            assertConsumed("file", thrown);
            assertEquals(1, requests.get());
            assertFalse(receivedBodies.get(0).contains("unwanted prefix"));
        }
    }

    @Test
    public void authenticationRetryFailsForAConsumedStreamPartAndClosesItsConnection() throws Exception {
        // With only one connection allowed, the next request can only be sent once the refused retry gave up the
        // connection kept for it.
        try (AsyncHttpClient client = asyncHttpClient(config().setMaxConnections(1))) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client
                    .preparePost(getTargetUrl() + AUTHENTICATE_FIRST)
                    .setRealm(basicAuthRealm(USER, ADMIN))
                    .addBodyPart(new InputStreamPart("file", resettable("payload"), "file.bin", "payload".length()))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS));

            assertConsumed("file", thrown);
            assertEquals(1, requests.get());
            assertEquals(200, client.prepareGet(getTargetUrl()).execute().get(TIMEOUT, TimeUnit.SECONDS).getStatusCode());
        }
    }

    @Test
    public void replaySendsTheFreshStreamPartOfAReplacedRequest() throws Exception {
        Part fresh = new InputStreamPart("fresh", nonResettable("fresh payload"), "fresh.bin");
        try (AsyncHttpClient client = clientReplacingParts(List.of(fresh))) {
            Response response = client.preparePost(getTargetUrl())
                    .addBodyPart(new InputStreamPart("first", nonResettable("first payload"), "first.bin"))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals(2, receivedBodies.size());
            assertTrue(receivedBodies.get(1).contains("fresh payload"));
        }
    }

    @Test
    public void replaySendsAFreshStreamPartFromItsPosition() throws Exception {
        InputStream stream = resettable("unwanted prefix|fresh payload");
        assertEquals("unwanted prefix|".length(), stream.skip("unwanted prefix|".length()));
        Part fresh = new InputStreamPart("fresh", stream, "fresh.bin");
        try (AsyncHttpClient client = clientReplacingParts(List.of(fresh))) {
            client.preparePost(getTargetUrl())
                    .addBodyPart(new InputStreamPart("first", resettable("first payload"), "first.bin"))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(2, receivedBodies.size());
            assertTrue(receivedBodies.get(1).contains("fresh payload"));
            assertFalse(receivedBodies.get(1).contains("unwanted prefix"));
        }
    }

    @Test
    public void replaySendsABodyThatTakesPrecedenceOverAConsumedStreamPart() throws Exception {
        // setBodyParts leaves the string body in place, and the string body is the one sent.
        Part consumed = new InputStreamPart("file", resettable("payload"), "file.bin");
        try (AsyncHttpClient client = clientReplayingOnce(builder -> builder
                .setBody("replacement body")
                .setBodyParts(List.of(consumed)))) {
            Response response = client.preparePost(getTargetUrl())
                    .addBodyPart(consumed)
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals(2, receivedBodies.size());
            assertEquals("replacement body", receivedBodies.get(1));
        }
    }

    @Test
    public void replaySendsAConnectThatKeepsAConsumedStreamPart() throws Exception {
        // A CONNECT has no body, so its parts are not sent.
        Part consumed = new InputStreamPart("file", resettable("payload"), "file.bin");
        try (AsyncHttpClient client = clientReplayingOnce(builder -> builder.setMethod("CONNECT"))) {
            Response response = client.preparePost(getTargetUrl())
                    .addBodyPart(consumed)
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(405, response.getStatusCode());
            assertEquals(2, requests.get());
        }
    }

    @Test
    public void replayFailsForAReusedStreamPartNextToAFreshOne() throws Exception {
        Part reused = new InputStreamPart("reused", resettable("reused payload"), "reused.bin");
        Part fresh = new InputStreamPart("fresh", nonResettable("fresh payload"), "fresh.bin");
        try (AsyncHttpClient client = clientReplacingParts(List.of(fresh, reused))) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.preparePost(getTargetUrl())
                    .addBodyPart(reused)
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS));

            assertConsumed("reused", thrown);
            assertEquals(1, requests.get());
        }
    }

    // Replays the first response's request once, with its body parts replaced by the given ones.
    private static AsyncHttpClient clientReplacingParts(List<Part> parts) {
        return clientReplayingOnce(builder -> builder.setBodyParts(parts));
    }

    // Replays the first response's request once, as changed by the given function.
    private static AsyncHttpClient clientReplayingOnce(UnaryOperator<RequestBuilder> change) {
        AtomicBoolean replay = new AtomicBoolean(true);
        ResponseFilter replaceOnce = new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (replay.getAndSet(false)) {
                    return new FilterContext.FilterContextBuilder<T>(ctx.getAsyncHandler(),
                            change.apply(ctx.getRequest().toBuilder()).build())
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };
        return asyncHttpClient(config().addResponseFilter(replaceOnce));
    }

    private static void assertConsumed(String partName, ExecutionException thrown) {
        IOException cause = assertInstanceOf(IOException.class, thrown.getCause());
        assertEquals("Multipart InputStream body part '" + partName + "' already consumed and cannot be sent again",
                cause.getMessage());
    }

    private static InputStream resettable(String content) {
        return new ByteArrayInputStream(content.getBytes(UTF_8));
    }

    // Like many wrappers, it ignores close() and returns end of stream once read, so sending it again would send an
    // empty part instead of failing.
    private static InputStream nonResettable(String content) {
        return new FilterInputStream(resettable(content)) {
            @Override
            public boolean markSupported() {
                return false;
            }
        };
    }
}
