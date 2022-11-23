/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.handler;

import io.github.artsok.RepeatedIfExceptionsTest;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Response;
import org.asynchttpclient.exception.RemotelyClosedException;
import org.asynchttpclient.handler.BodyDeferringAsyncHandler.BodyDeferringInputStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_OCTET_STREAM;
import static org.apache.commons.io.IOUtils.copy;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.findFreePort;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BodyDeferringAsyncHandlerTest extends AbstractBasicTest {

    static final int CONTENT_LENGTH_VALUE = 100000;

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new SlowAndBigHandler();
    }

    private static AsyncHttpClientConfig getAsyncHttpClientConfig() {
        // for this test brevity's sake, we are limiting to 1 retries
        return config().setMaxRequestRetry(0).setRequestTimeout(10000).build();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void deferredSimple() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(getAsyncHttpClientConfig())) {
            BoundRequestBuilder r = client.prepareGet(getTargetUrl());

            CountingOutputStream cos = new CountingOutputStream();
            BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(cos);
            Future<Response> f = r.execute(bdah);
            Response resp = bdah.getResponse();
            assertNotNull(resp);
            assertEquals(HttpServletResponse.SC_OK, resp.getStatusCode());
            assertEquals(String.valueOf(CONTENT_LENGTH_VALUE), resp.getHeader(CONTENT_LENGTH));

            // we got headers only, it's probably not all yet here (we have BIG file
            // downloading)
            assertTrue(cos.getByteCount() <= CONTENT_LENGTH_VALUE);

            // now be polite and wait for body arrival too (otherwise we would be
            // dropping the "line" on server)
            assertDoesNotThrow(() -> f.get());

            // it all should be here now
            assertEquals(cos.getByteCount(), CONTENT_LENGTH_VALUE);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void deferredSimpleWithFailure() throws Throwable {
        try (AsyncHttpClient client = asyncHttpClient(getAsyncHttpClientConfig())) {
            BoundRequestBuilder requestBuilder = client.prepareGet(getTargetUrl()).addHeader("X-FAIL-TRANSFER", Boolean.TRUE.toString());

            CountingOutputStream cos = new CountingOutputStream();
            BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(cos);
            Future<Response> f = requestBuilder.execute(bdah);
            Response resp = bdah.getResponse();
            assertNotNull(resp);
            assertEquals(HttpServletResponse.SC_OK, resp.getStatusCode());
            assertEquals(String.valueOf(CONTENT_LENGTH_VALUE), resp.getHeader(CONTENT_LENGTH));
            // we got headers only, it's probably not all yet here (we have BIG file
            // downloading)
            assertTrue(cos.getByteCount() <= CONTENT_LENGTH_VALUE);

            // now be polite and wait for body arrival too (otherwise we would be
            // dropping the "line" on server)
            try {
                assertThrows(ExecutionException.class, () -> f.get());
            } catch (Exception ex) {
                assertInstanceOf(RemotelyClosedException.class, ex.getCause());
            }
            assertNotEquals(CONTENT_LENGTH_VALUE, cos.getByteCount());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void deferredInputStreamTrick() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(getAsyncHttpClientConfig())) {
            BoundRequestBuilder r = client.prepareGet(getTargetUrl());

            PipedOutputStream pos = new PipedOutputStream();
            PipedInputStream pis = new PipedInputStream(pos);
            BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(pos);

            Future<Response> f = r.execute(bdah);

            BodyDeferringInputStream is = new BodyDeferringInputStream(f, bdah, pis);

            Response resp = is.getAsapResponse();
            assertNotNull(resp);
            assertEquals(HttpServletResponse.SC_OK, resp.getStatusCode());
            assertEquals(String.valueOf(CONTENT_LENGTH_VALUE), resp.getHeader(CONTENT_LENGTH));
            // "consume" the body, but our code needs input stream
            CountingOutputStream cos = new CountingOutputStream();
            try {
                copy(is, cos);
            } finally {
                is.close();
                cos.close();
            }

            // now we don't need to be polite, since consuming and closing
            // BodyDeferringInputStream does all.
            // it all should be here now
            assertEquals(CONTENT_LENGTH_VALUE, cos.getByteCount());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void deferredInputStreamTrickWithFailure() throws Throwable {
        try (AsyncHttpClient client = asyncHttpClient(getAsyncHttpClientConfig())) {
            BoundRequestBuilder r = client.prepareGet(getTargetUrl()).addHeader("X-FAIL-TRANSFER", Boolean.TRUE.toString());
            PipedOutputStream pos = new PipedOutputStream();
            PipedInputStream pis = new PipedInputStream(pos);
            BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(pos);

            Future<Response> f = r.execute(bdah);

            BodyDeferringInputStream is = new BodyDeferringInputStream(f, bdah, pis);

            Response resp = is.getAsapResponse();
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader(CONTENT_LENGTH), String.valueOf(CONTENT_LENGTH_VALUE));
            // "consume" the body, but our code needs input stream
            CountingOutputStream cos = new CountingOutputStream();

            try (is; cos) {
                copy(is, cos);
            } catch (Exception ex) {
                assertInstanceOf(RemotelyClosedException.class, ex.getCause());
            }
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void deferredInputStreamTrickWithCloseConnectionAndRetry() throws Throwable {
        try (AsyncHttpClient client = asyncHttpClient(config().setMaxRequestRetry(1).setRequestTimeout(10000).build())) {
            BoundRequestBuilder r = client.prepareGet(getTargetUrl()).addHeader("X-CLOSE-CONNECTION", Boolean.TRUE.toString());
            PipedOutputStream pos = new PipedOutputStream();
            PipedInputStream pis = new PipedInputStream(pos);
            BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(pos);

            Future<Response> f = r.execute(bdah);

            BodyDeferringInputStream is = new BodyDeferringInputStream(f, bdah, pis);

            Response resp = is.getAsapResponse();
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader(CONTENT_LENGTH), String.valueOf(CONTENT_LENGTH_VALUE));
            // "consume" the body, but our code needs input stream
            CountingOutputStream cos = new CountingOutputStream();

            try (is; cos) {
                copy(is, cos);
            } catch (Exception ex) {
                assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
            }
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testConnectionRefused() throws Exception {
        int newPortWithoutAnyoneListening = findFreePort();
        try (AsyncHttpClient client = asyncHttpClient(getAsyncHttpClientConfig())) {
            BoundRequestBuilder r = client.prepareGet("http://localhost:" + newPortWithoutAnyoneListening + "/testConnectionRefused");

            CountingOutputStream cos = new CountingOutputStream();
            BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(cos);
            r.execute(bdah);
            assertThrows(IOException.class, () -> bdah.getResponse());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testPipedStreams() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(getAsyncHttpClientConfig())) {
            PipedOutputStream pout = new PipedOutputStream();
            try (PipedInputStream pin = new PipedInputStream(pout)) {
                BodyDeferringAsyncHandler handler = new BodyDeferringAsyncHandler(pout);
                ListenableFuture<Response> respFut = client.prepareGet(getTargetUrl()).execute(handler);

                Response resp = handler.getResponse();
                assertEquals(200, resp.getStatusCode());

                try (BodyDeferringInputStream is = new BodyDeferringInputStream(respFut, handler, pin)) {
                    String body = IOUtils.toString(is, StandardCharsets.UTF_8);
                    System.out.println("Body: " + body);
                    assertTrue(body.contains("ABCDEF"));
                }
            }
        }
    }

    public static class SlowAndBigHandler extends AbstractHandler {

        @Override
        public void handle(String pathInContext, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {
            httpResponse.setStatus(200);
            httpResponse.setContentLength(CONTENT_LENGTH_VALUE);
            httpResponse.setContentType(APPLICATION_OCTET_STREAM.toString());

            httpResponse.flushBuffer();

            final boolean wantConnectionClose = httpRequest.getHeader("X-CLOSE-CONNECTION") != null;
            final boolean wantFailure = httpRequest.getHeader("X-FAIL-TRANSFER") != null;
            final boolean wantSlow = httpRequest.getHeader("X-SLOW") != null;

            OutputStream os = httpResponse.getOutputStream();
            for (int i = 0; i < CONTENT_LENGTH_VALUE; i++) {
                os.write(i % 255);

                if (wantSlow) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ex) {
                        // nuku
                    }
                }

                if (i > CONTENT_LENGTH_VALUE / 2) {
                    if (wantFailure) {
                        // kaboom
                        // yes, response is committed, but Jetty does aborts and
                        // drops connection
                        httpResponse.sendError(500);
                        break;
                    } else if (wantConnectionClose) {
                        // kaboom^2
                        httpResponse.getOutputStream().close();
                    }
                }
            }

            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();
        }
    }

    // a /dev/null but counting how many bytes it ditched
    public static class CountingOutputStream extends OutputStream {
        private int byteCount;

        @Override
        public void write(int b) {
            // /dev/null
            byteCount++;
        }

        int getByteCount() {
            return byteCount;
        }
    }
}
