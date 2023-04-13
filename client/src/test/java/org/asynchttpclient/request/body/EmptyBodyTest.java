/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.request.body;

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.handler.codec.http.HttpHeaders;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests case where response doesn't have body.
 *
 * @author Hubert Iwaniuk
 */
public class EmptyBodyTest extends AbstractBasicTest {

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new NoBodyResponseHandler();
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testEmptyBody() throws IOException {
        try (AsyncHttpClient ahc = asyncHttpClient()) {
            final AtomicBoolean err = new AtomicBoolean(false);
            final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
            final AtomicBoolean status = new AtomicBoolean(false);
            final AtomicInteger headers = new AtomicInteger(0);
            final CountDownLatch latch = new CountDownLatch(1);

            ahc.executeRequest(ahc.prepareGet(getTargetUrl()).build(), new AsyncHandler<Object>() {

                @Override
                public void onThrowable(Throwable t) {
                    fail("Got throwable.", t);
                    err.set(true);
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart e) throws Exception {
                    byte[] bytes = e.getBodyPartBytes();

                    if (bytes.length != 0) {
                        String s = new String(bytes);
                        logger.info("got part: {}", s);
                        logger.warn("Sampling stacktrace.", new Throwable("trace that, we should not get called for empty body."));
                        queue.put(s);
                    }
                    return State.CONTINUE;
                }

                @Override
                public State onStatusReceived(HttpResponseStatus e) {
                    status.set(true);
                    return AsyncHandler.State.CONTINUE;
                }

                @Override
                public State onHeadersReceived(HttpHeaders e) throws Exception {
                    if (headers.incrementAndGet() == 2) {
                        throw new Exception("Analyze this.");
                    }
                    return State.CONTINUE;
                }

                @Override
                public Object onCompleted() {
                    latch.countDown();
                    return null;
                }
            });

            try {
                assertTrue(latch.await(1, TimeUnit.SECONDS), "Latch failed.");
            } catch (InterruptedException e) {
                fail("Interrupted.", e);
            }
            assertFalse(err.get());
            assertEquals(0, queue.size());
            assertTrue(status.get());
            assertEquals(1, headers.get());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testPutEmptyBody() throws Exception {
        try (AsyncHttpClient ahc = asyncHttpClient()) {
            Response response = ahc.preparePut(getTargetUrl()).setBody("String").execute().get();

            assertNotNull(response);
            assertEquals(204, response.getStatusCode());
            assertEquals("", response.getResponseBody());
            assertNotNull(response.getResponseBodyAsStream());
            assertEquals(-1, response.getResponseBodyAsStream().read());
        }
    }

    private static class NoBodyResponseHandler extends AbstractHandler {

        @Override
        public void handle(String s, Request request, HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {

            if (!"PUT".equalsIgnoreCase(req.getMethod())) {
                resp.setStatus(HttpServletResponse.SC_OK);
            } else {
                resp.setStatus(204);
            }
            request.setHandled(true);
        }
    }
}
