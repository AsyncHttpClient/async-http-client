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
import org.asynchttpclient.handler.RedirectRefusedException;
import org.asynchttpclient.request.body.generator.ByteArrayBodyGenerator;
import org.asynchttpclient.request.body.generator.FileBodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.request.body.multipart.StringPart;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-origin arm must detect content for EVERY body representation {@code NettyRequestFactory.body}
 * can select. If one is missed the arm silently permits the very replay it exists to refuse, so this walks the
 * whole set rather than sampling it.
 * <p>
 * Cross-origin here is {@code localhost} to {@code 127.0.0.1} on one port - same server, different host
 * string, which {@code Uri.isSameBase} and RFC 6454 both call a different origin.
 */
public class RedirectPolicyBodyDetectionTest extends AbstractBasicTest {

    private static final String CONTENT = "sensitive-content";

    private final AtomicBoolean targetHit = new AtomicBoolean();

    private static File contentFile;

    @BeforeEach
    public void resetCaptures() {
        targetHit.set(false);
    }

    @Override
    public AbstractHandler configureHandler() {
        return new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request,
                               HttpServletResponse response) throws IOException, ServletException {
                // Drain the request body BEFORE responding. A server that answers while the client is still
                // writing a streaming body (multipart in particular) makes the write fail first, and the
                // exchange then reports that write failure instead of the policy refusal. The security
                // property holds either way - the other origin is never reached - but the cause is racy, so
                // the harness behaves like a real server to keep this deterministic.
                drain(request);
                if (target.endsWith("/redirect")) {
                    response.setStatus(307);
                    response.setHeader("Location", "http://127.0.0.1:" + port1 + "/target");
                } else {
                    targetHit.set(true);
                    response.setStatus(200);
                }
                response.getOutputStream().flush();
                baseRequest.setHandled(true);
            }
        };
    }

    private static void drain(HttpServletRequest request) throws IOException {
        byte[] buf = new byte[4096];
        while (request.getInputStream().read(buf) != -1) {
            // discard
        }
    }

    private static File contentFile() throws IOException {
        if (contentFile == null) {
            File file = Files.createTempFile("ahc-redirect-policy", ".txt").toFile();
            file.deleteOnExit();
            Files.write(file.toPath(), CONTENT.getBytes(StandardCharsets.UTF_8));
            contentFile = file;
        }
        return contentFile;
    }

    /**
     * One row per {@code BodyRepresentation} constant other than {@code NONE}. The names must match the enum's
     * constant names: {@link #bodyDetectionCoversEveryRepresentation()} asserts that correspondence, so an
     * added representation fails the build here rather than silently going undetected in production.
     */
    private static Stream<Arguments> bodyRepresentations() throws IOException {
        byte[] bytes = CONTENT.getBytes(StandardCharsets.UTF_8);
        File file = contentFile();
        return Stream.of(
                Arguments.of("BYTE_DATA", (Consumer<RequestBuilder>) b -> b.setBody(bytes)),
                Arguments.of("COMPOSITE_BYTE_DATA", (Consumer<RequestBuilder>) b -> {
                    List<byte[]> parts = Arrays.asList(new byte[]{'a'}, new byte[]{'b'});
                    b.setBody(parts);
                }),
                Arguments.of("STRING_DATA", (Consumer<RequestBuilder>) b -> b.setBody(CONTENT)),
                Arguments.of("BYTE_BUFFER_DATA", (Consumer<RequestBuilder>) b -> b.setBody(ByteBuffer.wrap(bytes))),
                Arguments.of("BYTE_BUF_DATA", (Consumer<RequestBuilder>) b -> b.setBody(Unpooled.wrappedBuffer(bytes))),
                Arguments.of("STREAM_DATA", (Consumer<RequestBuilder>) b -> b.setBody(new ByteArrayInputStream(bytes))),
                Arguments.of("FORM_PARAMS", (Consumer<RequestBuilder>) b ->
                        b.setFormParams(Collections.singletonList(new Param("secret", CONTENT)))),
                Arguments.of("BODY_PARTS", (Consumer<RequestBuilder>) b -> b.addBodyPart(new StringPart("p", CONTENT))),
                Arguments.of("FILE", (Consumer<RequestBuilder>) b -> b.setBody(file)),
                Arguments.of("FILE_BODY_GENERATOR", (Consumer<RequestBuilder>) b -> b.setBody(new FileBodyGenerator(file))),
                Arguments.of("INPUT_STREAM_BODY_GENERATOR", (Consumer<RequestBuilder>) b ->
                        b.setBody(new InputStreamBodyGenerator(new ByteArrayInputStream(bytes)))),
                Arguments.of("BODY_GENERATOR", (Consumer<RequestBuilder>) b -> b.setBody(new ByteArrayBodyGenerator(bytes))));
    }

    @ParameterizedTest(name = "a {0} body is detected and its cross-origin replay refused")
    @MethodSource("bodyRepresentations")
    public void everyBodyRepresentationIsDetected(String representation, Consumer<RequestBuilder> withBody)
            throws Exception {
        AsyncHttpClientConfig cfg = config()
                .setFollowRedirect(true)
                .setRedirectPolicy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY)
                .build();
        try (AsyncHttpClient client = asyncHttpClient(cfg)) {
            RequestBuilder builder = new RequestBuilder("PUT").setUrl("http://localhost:" + port1 + "/redirect");
            withBody.accept(builder);

            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> client.executeRequest(builder.build()).get(TIMEOUT, TimeUnit.SECONDS),
                    representation + " must be detected as content");
            assertInstanceOf(RedirectRefusedException.class, e.getCause(),
                    representation + " was not detected as content; cause was " + e.getCause());
            assertFalse(targetHit.get(), representation + " reached the other origin");
        }
    }

    @Test
    public void noBodyIsNotTreatedAsContent() throws Exception {
        AsyncHttpClientConfig cfg = config()
                .setFollowRedirect(true)
                .setRedirectPolicy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY)
                .build();
        try (AsyncHttpClient client = asyncHttpClient(cfg)) {
            Response response = client.prepareGet("http://localhost:" + port1 + "/redirect")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertTrue(targetHit.get(), "a request with no content has nothing to protect");
        }
    }

    @Test
    public void emptyByteArrayCountsAsContent() throws Exception {
        // The factory selects a body for it, so the request does carry content framing even though it is empty.
        AsyncHttpClientConfig cfg = config()
                .setFollowRedirect(true)
                .setRedirectPolicy(RedirectPolicy.REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY)
                .build();
        try (AsyncHttpClient client = asyncHttpClient(cfg)) {
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> client.preparePut("http://localhost:" + port1 + "/redirect")
                            .setBody(new byte[0])
                            .execute().get(TIMEOUT, TimeUnit.SECONDS));
            assertInstanceOf(RedirectRefusedException.class, e.getCause());
        }
    }

    /**
     * Guards the parameter set against the enum it mirrors. A representation added to
     * {@code Redirect30xInterceptor.BodyRepresentation} without a row here fails this test, which is the signal
     * that the new representation needs checking against {@code NettyRequestFactory.body} too.
     * <p>
     * {@code Class.forName} on the private nested enum is possible because there is no {@code module-info.java}
     * anywhere in this project; if JPMS is ever introduced this needs revisiting rather than deleting.
     */
    @Test
    public void bodyDetectionCoversEveryRepresentation() throws Exception {
        Class<?> enumClass = Class.forName(
                "org.asynchttpclient.netty.handler.intercept.Redirect30xInterceptor$BodyRepresentation");
        Object[] constants = enumClass.getEnumConstants();
        assertNotNull(constants, "BodyRepresentation must still be an enum");

        Set<String> declared = Arrays.stream(constants)
                .map(c -> ((Enum<?>) c).name())
                .filter(name -> !"NONE".equals(name))
                .collect(Collectors.toSet());

        Set<String> covered = bodyRepresentations()
                .map(args -> (String) args.get()[0])
                .collect(Collectors.toSet());

        assertEquals(declared, covered,
                "every BodyRepresentation other than NONE needs a row in bodyRepresentations()");
    }
}
