/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.request.body.generator.ByteArrayBodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.request.body.multipart.InputStreamPart;
import org.asynchttpclient.request.body.multipart.StringPart;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.EXPECT;
import static io.netty.handler.codec.http.HttpHeaderNames.LOCATION;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.netty.handler.intercept.Redirect30xInterceptor.REDIRECT_STATUSES;
import static org.asynchttpclient.util.HttpConstants.Methods.GET;
import static org.asynchttpclient.util.HttpConstants.Methods.POST;
import static org.asynchttpclient.util.HttpConstants.Methods.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RedirectBodyTest extends AbstractBasicTest {

    private static final byte[] REDIRECT_BODY = "redirect body".getBytes(UTF_8);
    private static final String CONTENT_TYPE_VALUE = "application/octet-stream";
    private static final String NON_REPLAYABLE_STREAM_MESSAGE =
            "Redirect request body InputStream does not support mark/reset and cannot be replayed";

    private static final List<String> receivedContentLengths = new CopyOnWriteArrayList<>();
    private static volatile boolean redirectAlreadyPerformed;
    private static volatile byte[] receivedBody;
    private static volatile String receivedContentType;
    private static volatile String receivedMethod;
    private static volatile Path fileToDeleteBeforeRedirect;
    private static final AtomicInteger deferredStreamReads = new AtomicInteger();
    private static volatile int streamReadsBeforeRedirect;
    private static volatile long bodyBytesBeforeRedirect;
    private static volatile boolean expectingContinueBeforeRedirect;

    @BeforeEach
    public void setUp() {
        receivedContentLengths.clear();
        redirectAlreadyPerformed = false;
        receivedBody = null;
        receivedContentType = null;
        receivedMethod = null;
        fileToDeleteBeforeRedirect = null;
        deferredStreamReads.set(0);
        streamReadsBeforeRedirect = -1;
        bodyBytesBeforeRedirect = -1;
        expectingContinueBeforeRedirect = false;
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {
            @Override
            public void handle(String pathInContext, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {
                if (pathInContext.endsWith("/deferred-redirect")) {
                    streamReadsBeforeRedirect = deferredStreamReads.get();
                    bodyBytesBeforeRedirect = request.getHttpInput().getContentReceived();
                    expectingContinueBeforeRedirect = request.getHttpChannel().isExpecting100Continue();
                    // Reading the request input here would trigger Jetty's automatic 100 Continue.
                    httpResponse.setStatus(307);
                    httpResponse.setContentLength(0);
                    httpResponse.setHeader(LOCATION.toString(), getTargetUrl());
                    httpResponse.setHeader(CONNECTION.toString(), "close");
                    request.setHandled(true);
                    httpResponse.flushBuffer();
                    return;
                }

                byte[] body = IOUtils.toByteArray(request.getInputStream());
                receivedContentLengths.add(String.valueOf(httpRequest.getHeader(CONTENT_LENGTH.toString())));
                String redirectHeader = httpRequest.getHeader("X-REDIRECT");
                if (redirectHeader != null && !redirectAlreadyPerformed) {
                    redirectAlreadyPerformed = true;
                    if (fileToDeleteBeforeRedirect != null) {
                        Files.deleteIfExists(fileToDeleteBeforeRedirect);
                    }
                    httpResponse.setStatus(Integer.valueOf(redirectHeader));
                    httpResponse.setContentLength(0);
                    httpResponse.setHeader(LOCATION.toString(), getTargetUrl());

                } else {
                    receivedBody = body;
                    receivedContentType = request.getContentType();
                    receivedMethod = request.getMethod();
                    httpResponse.setStatus(200);
                    httpResponse.setContentLength(body.length);
                    if (body.length > 0) {
                        httpResponse.getOutputStream().write(body);
                    }
                }
                httpResponse.getOutputStream().flush();
                httpResponse.getOutputStream().close();
            }
        };
    }

    @Test
    public void regular301LosesBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "301").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), "");
            assertEquals(GET, receivedMethod);
            assertNull(receivedContentType);
        }
    }

    @Test
    public void regular302LosesBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "302").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), "");
            assertEquals(GET, receivedMethod);
            assertNull(receivedContentType);
        }
    }

    @Test
    public void regular302StrictKeepsBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true).setStrict302Handling(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "302").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), body);
            assertEquals(POST, receivedMethod);
            assertEquals(receivedContentType, contentType);
        }
    }

    @Test
    public void regular303SwitchesToGetAndLosesBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "303").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals("", response.getResponseBody());
            assertEquals(GET, receivedMethod);
            assertNull(receivedContentType);
        }
    }

    @Test
    public void regular307KeepsBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "307").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), body);
            assertEquals(POST, receivedMethod);
            assertEquals(receivedContentType, contentType);
        }
    }

    @Test
    public void regular308KeepsBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "308").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(body, response.getResponseBody());
            assertEquals(POST, receivedMethod);
            assertEquals(contentType, receivedContentType);
        }
    }

    @Test
    public void query301KeepsMethodAndBody() throws Exception {
        queryRedirectKeepsMethodAndBody(301, false);
    }

    @Test
    public void query302KeepsMethodAndBody() throws Exception {
        queryRedirectKeepsMethodAndBody(302, false);
    }

    @Test
    public void query302StrictKeepsMethodAndBody() throws Exception {
        queryRedirectKeepsMethodAndBody(302, true);
    }

    @Test
    public void query303SwitchesToGetAndDropsBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            Response response = c.prepare(QUERY, getTargetUrl())
                    .setHeader(CONTENT_TYPE, contentType)
                    .setBody(body)
                    .setHeader("X-REDIRECT", "303")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals("", response.getResponseBody());
            assertEquals(GET, receivedMethod);
            assertNull(receivedContentType);
        }
    }

    @Test
    public void query307KeepsMethodAndBody() throws Exception {
        queryRedirectKeepsMethodAndBody(307, false);
    }

    @Test
    public void query308KeepsMethodAndBody() throws Exception {
        queryRedirectKeepsMethodAndBody(308, false);
    }

    @Test
    public void query301KeepsRepeatableBodyGenerator() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            byte[] body = "hello there".getBytes(UTF_8);
            String contentType = "text/plain; charset=UTF-8";

            Response response = c.prepare(QUERY, getTargetUrl())
                    .setHeader(CONTENT_TYPE, contentType)
                    .setBody(new ByteArrayBodyGenerator(body))
                    .setHeader("X-REDIRECT", "301")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals("hello there", response.getResponseBody());
            assertEquals(QUERY, receivedMethod);
            assertEquals(contentType, receivedContentType);
        }
    }

    @Test
    public void query301WithNonRepeatableBodyGeneratorFailsPromptly() throws Exception {
        try (InputStream body = new FilterInputStream(new ByteArrayInputStream(REDIRECT_BODY)) {
            @Override
            public boolean markSupported() {
                return false;
            }

            @Override
            public synchronized void reset() throws IOException {
                throw new IOException("reset not supported");
            }
        };
             AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> c.prepare(QUERY, getTargetUrl())
                            .setBody(new InputStreamBodyGenerator(body))
                            .setHeader("X-REDIRECT", "301")
                            .execute()
                            .get(TIMEOUT, TimeUnit.SECONDS));

            IOException cause = assertInstanceOf(IOException.class, thrown.getCause());
            assertEquals(NON_REPLAYABLE_STREAM_MESSAGE, cause.getMessage());
        }
    }

    @Test
    public void put301WithNonRepeatableBodyGeneratorFailsPromptly() throws Exception {
        try (InputStream body = new FilterInputStream(new ByteArrayInputStream(REDIRECT_BODY)) {
            @Override
            public boolean markSupported() {
                return false;
            }

            @Override
            public synchronized void reset() throws IOException {
                throw new IOException("reset not supported");
            }
        };
             AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> c.preparePut(getTargetUrl())
                            .setBody(new InputStreamBodyGenerator(body))
                            .setHeader("X-REDIRECT", "301")
                            .execute()
                            .get(TIMEOUT, TimeUnit.SECONDS));

            IOException cause = assertInstanceOf(IOException.class, thrown.getCause());
            assertEquals(NON_REPLAYABLE_STREAM_MESSAGE, cause.getMessage());
        }
    }

    @ParameterizedTest(name = "307 before 100 Continue keeps an untouched stream (generator={0})")
    @ValueSource(booleans = {false, true})
    public void deferredInputStream307KeepsBody(boolean useBodyGenerator) throws Exception {
        try (InputStream body = new FilterInputStream(new ByteArrayInputStream(REDIRECT_BODY)) {
            @Override
            public int read() throws IOException {
                deferredStreamReads.incrementAndGet();
                return in.read();
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                deferredStreamReads.incrementAndGet();
                return in.read(bytes, offset, length);
            }

            @Override
            public boolean markSupported() {
                return false;
            }

            @Override
            public synchronized void reset() throws IOException {
                throw new IOException("reset not supported");
            }
        };
             AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            BoundRequestBuilder builder = c.preparePut(getTargetUrl() + "/deferred-redirect")
                    .setHeader(EXPECT, "100-continue")
                    .setHeader(CONTENT_TYPE, CONTENT_TYPE_VALUE)
                    .setHeader(CONTENT_LENGTH, REDIRECT_BODY.length);
            if (useBodyGenerator) {
                builder.setBody(new InputStreamBodyGenerator(body));
            } else {
                builder.setBody(body);
            }

            Response response = builder.execute().get(TIMEOUT, TimeUnit.SECONDS);

            assertTrue(expectingContinueBeforeRedirect);
            assertEquals(0, streamReadsBeforeRedirect);
            assertEquals(0, bodyBytesBeforeRedirect);
            assertArrayEquals(REDIRECT_BODY, receivedBody);
            assertEquals("PUT", receivedMethod);
            assertRedirectBody(response);
        }
    }

    @ParameterizedTest(name = "{0} on {1} keeps its method and body")
    @CsvSource({
            "PUT, 301",
            "PUT, 302",
            "PATCH, 301",
            "PATCH, 302",
            "DELETE, 301",
            "DELETE, 302",
            "CUSTOM, 301",
            "CUSTOM, 302",
            "GET, 301",
            "GET, 302",
            "HEAD, 301",
            "HEAD, 302",
            "OPTIONS, 301",
            "OPTIONS, 302"
    })
    public void nonPost301And302KeepMethodAndBody(String method, int statusCode) throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            c.prepare(method, getTargetUrl())
                    .setHeader(CONTENT_TYPE, contentType)
                    .setBody(body)
                    .setHeader("X-REDIRECT", Integer.toString(statusCode))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);
            assertArrayEquals(body.getBytes(UTF_8), receivedBody);
            assertEquals(method, receivedMethod);
            assertEquals(contentType, receivedContentType);
        }
    }

    @Test
    public void put301AcrossDifferentHostsKeepsMethodAndBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";
            String originalUrl = getTargetUrl().replace("localhost", "127.0.0.1");

            c.preparePut(originalUrl)
                    .setHeader(CONTENT_TYPE, contentType)
                    .setBody(body)
                    .setHeader("X-REDIRECT", "301")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);
            assertArrayEquals(body.getBytes(UTF_8), receivedBody);
            assertEquals("PUT", receivedMethod);
            assertEquals(contentType, receivedContentType);
        }
    }

    @ParameterizedTest(name = "{0} on caller-added 300 keeps its method and body")
    @CsvSource({"POST", "PUT"})
    public void callerAddedRedirectStatusKeepsMethodAndBody(String method) throws Exception {
        boolean added = REDIRECT_STATUSES.add(300);
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            c.prepare(method, getTargetUrl())
                    .setHeader(CONTENT_TYPE, contentType)
                    .setBody(body)
                    .setHeader("X-REDIRECT", "300")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);
            assertArrayEquals(body.getBytes(UTF_8), receivedBody);
            assertEquals(method, receivedMethod);
            assertEquals(contentType, receivedContentType);
        } finally {
            if (added) {
                REDIRECT_STATUSES.remove(300);
            }
        }
    }

    @Test
    public void redirectPreservesPerRequestSettings() throws Exception {
        Duration readTimeout = Duration.ofSeconds(7);
        long rangeOffset = 41L;
        List<Duration> observedReadTimeouts = new CopyOnWriteArrayList<>();
        List<Long> observedRangeOffsets = new CopyOnWriteArrayList<>();
        ResponseFilter observer = new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                observedReadTimeouts.add(ctx.getRequest().getReadTimeout());
                observedRangeOffsets.add(ctx.getRequest().getRangeOffset());
                return ctx;
            }
        };

        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true).addResponseFilter(observer))) {
            Response response = c.preparePost(getTargetUrl())
                    .setReadTimeout(readTimeout)
                    .setRangeOffset(rangeOffset)
                    .setBody(REDIRECT_BODY)
                    .setHeader("X-REDIRECT", "307")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertArrayEquals(REDIRECT_BODY, response.getResponseBodyAsBytes());
            assertEquals(List.of(readTimeout, readTimeout), observedReadTimeouts);
            assertEquals(List.of(rangeOffset, rangeOffset), observedRangeOffsets);
        }
    }

    @Test
    public void bodylessRedirectPreservesPerRequestSettings() throws Exception {
        Duration readTimeout = Duration.ofSeconds(7);
        long rangeOffset = 41L;
        List<Duration> observedReadTimeouts = new CopyOnWriteArrayList<>();
        List<Long> observedRangeOffsets = new CopyOnWriteArrayList<>();
        ResponseFilter observer = new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                observedReadTimeouts.add(ctx.getRequest().getReadTimeout());
                observedRangeOffsets.add(ctx.getRequest().getRangeOffset());
                return ctx;
            }
        };

        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true).addResponseFilter(observer))) {
            Response response = c.preparePost(getTargetUrl())
                    .setReadTimeout(readTimeout)
                    .setRangeOffset(rangeOffset)
                    .setBody(REDIRECT_BODY)
                    .setHeader("X-REDIRECT", "303")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals("", response.getResponseBody());
            assertEquals(List.of(readTimeout, readTimeout), observedReadTimeouts);
            assertEquals(List.of(rangeOffset, rangeOffset), observedRangeOffsets);
        }
    }

    @Test
    public void compositeByteArray307KeepsBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            byte[] first = "redirect ".getBytes(UTF_8);
            byte[] second = "body".getBytes(UTF_8);

            Response response = execute307(c.preparePost(getTargetUrl()).setBody(Arrays.asList(first, second)));

            assertRedirectBody(response);
        }
    }

    @Test
    public void byteBuf307KeepsBody() throws Exception {
        ByteBuf body = Unpooled.wrappedBuffer(REDIRECT_BODY);
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            Response response = execute307(c.preparePost(getTargetUrl()).setBody(body));

            assertRedirectBody(response);
            assertEquals(1, body.refCnt(), "the caller must retain ownership of its ByteBuf");
        } finally {
            body.release();
        }
    }

    @Test
    public void resettableInputStream307KeepsBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            Response response = execute307(c.preparePost(getTargetUrl()).setBody(new ByteArrayInputStream(REDIRECT_BODY)));

            assertRedirectBody(response);
        }
    }

    @Test
    public void inputStream307PreservesExplicitContentLength() throws Exception {
        try (InputStream body = new ByteArrayInputStream(REDIRECT_BODY);
             AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            Response response = execute307(c.preparePost(getTargetUrl())
                    .setHeader(CONTENT_LENGTH, REDIRECT_BODY.length)
                    .setBody(body));

            assertRedirectBody(response);
            String expectedLength = Integer.toString(REDIRECT_BODY.length);
            assertEquals(List.of(expectedLength, expectedLength), receivedContentLengths);
        }
    }

    @Test
    public void inputStreamBodyGenerator307PreservesExplicitContentLength() throws Exception {
        try (InputStream body = new ByteArrayInputStream(REDIRECT_BODY);
             AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            Response response = execute307(c.preparePost(getTargetUrl())
                    .setHeader(CONTENT_LENGTH, REDIRECT_BODY.length)
                    .setBody(new InputStreamBodyGenerator(body)));

            assertRedirectBody(response);
            String expectedLength = Integer.toString(REDIRECT_BODY.length);
            assertEquals(List.of(expectedLength, expectedLength), receivedContentLengths);
        }
    }

    @Test
    public void nonResettableInputStream307FailsPromptly() throws Exception {
        try (InputStream body = new FilterInputStream(new ByteArrayInputStream(REDIRECT_BODY)) {
            @Override
            public boolean markSupported() {
                return false;
            }

            @Override
            public synchronized void reset() throws IOException {
                throw new IOException("reset not supported");
            }
        };
             AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> execute307(c.preparePost(getTargetUrl()).setBody(body)));

            IOException cause = assertInstanceOf(IOException.class, thrown.getCause());
            assertEquals(NON_REPLAYABLE_STREAM_MESSAGE, cause.getMessage());
        }
    }

    @Test
    public void fileInputStream307FailsPromptly() throws Exception {
        Path bodyFile = Files.createTempFile("ahc-redirect-stream-", ".bin");
        try {
            Files.write(bodyFile, REDIRECT_BODY);
            try (InputStream body = Files.newInputStream(bodyFile);
                 AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
                ExecutionException thrown = assertThrows(ExecutionException.class,
                        () -> execute307(c.preparePost(getTargetUrl()).setBody(body)));

                IOException cause = assertInstanceOf(IOException.class, thrown.getCause());
                assertEquals(NON_REPLAYABLE_STREAM_MESSAGE, cause.getMessage());
            }
        } finally {
            Files.deleteIfExists(bodyFile);
        }
    }

    @Test
    public void file307KeepsBody() throws Exception {
        Path body = Files.createTempFile("ahc-redirect-body-", ".bin");
        try {
            Files.write(body, REDIRECT_BODY);
            try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
                Response response = execute307(c.preparePost(getTargetUrl()).setBody(body.toFile()));

                assertRedirectBody(response);
            }
        } finally {
            Files.deleteIfExists(body);
        }
    }

    @Test
    public void vanishedFile307FailsPromptly() throws Exception {
        Path body = Files.createTempFile("ahc-redirect-vanished-", ".bin");
        try {
            Files.write(body, REDIRECT_BODY);
            // Deleted here, not in the server handler: Windows cannot delete a file the client still has open.
            // The 307 only arrives after the whole body was sent, and by then the client has closed the file.
            // Not true for /deferred-redirect, nor with TLS or disableZeroCopy.
            AtomicBoolean vanished = new AtomicBoolean();
            AtomicReference<IOException> vanishFailure = new AtomicReference<>();
            ResponseFilter vanisher = new ResponseFilter() {
                @Override
                public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                    HttpResponseStatus status = ctx.getResponseStatus();
                    if (status != null && status.getStatusCode() == 307) {
                        try {
                            if (Files.deleteIfExists(body)) {
                                vanished.set(true);
                            }
                        } catch (IOException e) {
                            vanishFailure.set(e);
                        }
                    }
                    return ctx;
                }
            };

            try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true).addResponseFilter(vanisher))) {
                ExecutionException thrown = null;
                try {
                    execute307(c.preparePost(getTargetUrl()).setBody(body.toFile()));
                } catch (ExecutionException e) {
                    thrown = e;
                }

                assertNull(vanishFailure.get(), "request body file should be deletable once the region is released");
                assertTrue(vanished.get(), "the 307 never reached the response filter");
                assertNotNull(thrown, "the redirect replay should have failed once the body vanished");
                // NettyFileBody rejects a missing file too, so the type and message must be checked.
                IOException cause = assertInstanceOf(IOException.class, thrown.getCause());
                assertEquals("Redirect request body file " + body.toAbsolutePath()
                        + " is not a file or does not exist", cause.getMessage());
            }
        } finally {
            Files.deleteIfExists(body);
        }
    }

    @Test
    public void coexistingFileAndByteArray308UsesByteArray() throws Exception {
        Path file = Files.createTempFile("ahc-redirect-precedence-", ".bin");
        try {
            Files.write(file, "wrong file body".getBytes(UTF_8));
            fileToDeleteBeforeRedirect = file;
            try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
                Response response = c.preparePost(getTargetUrl())
                        .setBody(file.toFile())
                        .setBody(REDIRECT_BODY)
                        .setHeader(CONTENT_TYPE, CONTENT_TYPE_VALUE)
                        .setHeader("X-REDIRECT", "308")
                        .execute()
                        .get(TIMEOUT, TimeUnit.SECONDS);

                assertRedirectBody(response);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void coexistingMultipartStreamAndByteArray307UsesByteArray() throws Exception {
        try (InputStream unusedPart = new ByteArrayInputStream("unused part".getBytes(UTF_8));
             AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            Response response = execute307(c.preparePost(getTargetUrl())
                    .setBody(REDIRECT_BODY)
                    .setBodyParts(List.of(new InputStreamPart("file", unusedPart, "unused.bin",
                            "unused part".length(), CONTENT_TYPE_VALUE))));

            assertRedirectBody(response);
        }
    }

    @Test
    public void formParams307KeepBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            Response response = c.preparePost(getTargetUrl())
                    .addFormParam("field", "value")
                    .setHeader("X-REDIRECT", "307")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals("field=value", response.getResponseBody());
        }
    }

    @Test
    public void multipart307KeepsBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            Response response = c.preparePost(getTargetUrl())
                    .addBodyPart(new StringPart("field", "multipart value"))
                    .setHeader("X-REDIRECT", "307")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertTrue(response.getResponseBody().contains("multipart value"));
        }
    }

    @Test
    public void inputStreamMultipart307FailsPromptly() throws Exception {
        Path bodyFile = Files.createTempFile("ahc-redirect-multipart-", ".bin");
        try {
            Files.write(bodyFile, REDIRECT_BODY);
            try (InputStream body = Files.newInputStream(bodyFile);
                 AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
                ExecutionException thrown = assertThrows(ExecutionException.class,
                        () -> c.preparePost(getTargetUrl())
                                .addBodyPart(new InputStreamPart("file", body, bodyFile.getFileName().toString(),
                                        REDIRECT_BODY.length, CONTENT_TYPE_VALUE))
                                .setHeader("X-REDIRECT", "307")
                                .execute()
                                .get(TIMEOUT, TimeUnit.SECONDS));

                IOException cause = assertInstanceOf(IOException.class, thrown.getCause());
                assertEquals("Multipart InputStream body part 'file' cannot be replayed after redirect",
                        cause.getMessage());
            }
        } finally {
            Files.deleteIfExists(bodyFile);
        }
    }

    private void queryRedirectKeepsMethodAndBody(int statusCode, boolean strict302Handling) throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config()
                .setFollowRedirect(true)
                .setStrict302Handling(strict302Handling))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            Response response = c.prepare(QUERY, getTargetUrl())
                    .setHeader(CONTENT_TYPE, contentType)
                    .setBody(body)
                    .setHeader("X-REDIRECT", Integer.toString(statusCode))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(body, response.getResponseBody());
            assertEquals(QUERY, receivedMethod);
            assertEquals(contentType, receivedContentType);
        }
    }

    private static Response execute307(BoundRequestBuilder requestBuilder) throws Exception {
        return requestBuilder
                .setHeader(CONTENT_TYPE, CONTENT_TYPE_VALUE)
                .setHeader("X-REDIRECT", "307")
                .execute()
                .get(TIMEOUT, TimeUnit.SECONDS);
    }

    private static void assertRedirectBody(Response response) {
        assertArrayEquals(REDIRECT_BODY, response.getResponseBodyAsBytes());
        assertEquals(CONTENT_TYPE_VALUE, receivedContentType);
    }
}
