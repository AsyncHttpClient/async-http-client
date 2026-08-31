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

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.request.body.multipart.InputStreamPart;
import org.asynchttpclient.request.body.multipart.StringPart;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;

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

import static java.nio.charset.StandardCharsets.UTF_8;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.LOCATION;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RedirectBodyTest extends AbstractBasicTest {

    private static final byte[] REDIRECT_BODY = "redirect body".getBytes(UTF_8);
    private static final String CONTENT_TYPE_VALUE = "application/octet-stream";

    private static final List<String> receivedContentLengths = new CopyOnWriteArrayList<>();
    private static volatile boolean redirectAlreadyPerformed;
    private static volatile String receivedContentType;
    private static volatile Path fileToDeleteBeforeRedirect;

    @BeforeEach
    public void setUp() {
        receivedContentLengths.clear();
        redirectAlreadyPerformed = false;
        receivedContentType = null;
        fileToDeleteBeforeRedirect = null;
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {
            @Override
            public void handle(String pathInContext, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {

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
                    receivedContentType = request.getContentType();
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

    @RepeatedIfExceptionsTest(repeats = 5)
    public void regular301LosesBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "301").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), "");
            assertNull(receivedContentType);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void regular302LosesBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "302").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), "");
            assertNull(receivedContentType);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void regular302StrictKeepsBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true).setStrict302Handling(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "302").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), body);
            assertEquals(receivedContentType, contentType);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void regular307KeepsBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            String body = "hello there";
            String contentType = "text/plain; charset=UTF-8";

            Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "307").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), body);
            assertEquals(receivedContentType, contentType);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
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

    @RepeatedIfExceptionsTest(repeats = 5)
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

    @RepeatedIfExceptionsTest(repeats = 5)
    public void compositeByteArray307KeepsBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            byte[] first = "redirect ".getBytes(UTF_8);
            byte[] second = "body".getBytes(UTF_8);

            Response response = execute307(c.preparePost(getTargetUrl()).setBody(Arrays.asList(first, second)));

            assertRedirectBody(response);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
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

    @RepeatedIfExceptionsTest(repeats = 5)
    public void resettableInputStream307KeepsBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            Response response = execute307(c.preparePost(getTargetUrl()).setBody(new ByteArrayInputStream(REDIRECT_BODY)));

            assertRedirectBody(response);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
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

    @RepeatedIfExceptionsTest(repeats = 5)
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

    @RepeatedIfExceptionsTest(repeats = 5)
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
            assertEquals("HTTP/1 request body InputStream already consumed and cannot be reset for a retry",
                    cause.getMessage());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void fileInputStream307FailsPromptly() throws Exception {
        Path bodyFile = Files.createTempFile("ahc-redirect-stream-", ".bin");
        try {
            Files.write(bodyFile, REDIRECT_BODY);
            try (InputStream body = Files.newInputStream(bodyFile);
                 AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
                ExecutionException thrown = assertThrows(ExecutionException.class,
                        () -> execute307(c.preparePost(getTargetUrl()).setBody(body)));

                IOException cause = assertInstanceOf(IOException.class, thrown.getCause());
                assertEquals("HTTP/1 request body InputStream already consumed and cannot be reset for a retry",
                        cause.getMessage());
            }
        } finally {
            Files.deleteIfExists(bodyFile);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
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

    @RepeatedIfExceptionsTest(repeats = 5)
    public void vanishedFile307FailsPromptly() throws Exception {
        Path body = Files.createTempFile("ahc-redirect-vanished-", ".bin");
        try {
            Files.write(body, REDIRECT_BODY);
            fileToDeleteBeforeRedirect = body;
            try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
                ExecutionException thrown = assertThrows(ExecutionException.class,
                        () -> execute307(c.preparePost(getTargetUrl()).setBody(body.toFile())));

                IOException cause = assertInstanceOf(IOException.class, thrown.getCause());
                assertEquals("Redirect request body file " + body.toAbsolutePath()
                        + " is not a file or does not exist", cause.getMessage());
            }
        } finally {
            Files.deleteIfExists(body);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
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

    @RepeatedIfExceptionsTest(repeats = 5)
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

    @RepeatedIfExceptionsTest(repeats = 5)
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

    @RepeatedIfExceptionsTest(repeats = 5)
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
