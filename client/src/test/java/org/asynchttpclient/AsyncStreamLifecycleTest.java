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
package org.asynchttpclient;

import io.netty.handler.codec.http.HttpHeaders;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests default asynchronous life cycle.
 *
 * @author Hubert Iwaniuk
 */
public class AsyncStreamLifecycleTest extends AbstractBasicTest {
    // One thread, so the two parts are written in order.
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();

    // Counted down by the client on its first body part. The server writes the second part only after that.
    private volatile CountDownLatch firstPartReceived = new CountDownLatch(1);
    private volatile boolean handshakeTimedOut;

    @Override
    @AfterAll
    public void tearDownGlobal() throws Exception {
        super.tearDownGlobal();
        executorService.shutdownNow();
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {
            @Override
            public void handle(String s, Request request, HttpServletRequest req, final HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain;charset=utf-8");
                resp.setStatus(200);
                final AsyncContext asyncContext = request.startAsync();
                final PrintWriter writer = resp.getWriter();
                executorService.submit(() -> {
                    try {
                        logger.info("Delivering part1.");
                        writer.write("part1");
                        writer.flush();
                        if (!firstPartReceived.await(TIMEOUT, TimeUnit.SECONDS)) {
                            // Writing part2 anyway would let the test pass without the ordering it checks.
                            handshakeTimedOut = true;
                            return;
                        }
                        logger.info("Delivering part2.");
                        writer.write("part2");
                        writer.flush();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.error("Interrupted while waiting for part1 to be received.", e);
                    } finally {
                        asyncContext.complete();
                    }
                });
                request.setHandled(true);
            }
        };
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void testStream() throws Exception {
        firstPartReceived = new CountDownLatch(1);
        handshakeTimedOut = false;
        try (AsyncHttpClient ahc = asyncHttpClient()) {
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
            final AtomicBoolean status = new AtomicBoolean(false);
            final AtomicInteger headers = new AtomicInteger(0);
            final CountDownLatch latch = new CountDownLatch(1);
            ahc.executeRequest(ahc.prepareGet(getTargetUrl()).build(), new AsyncHandler<Object>() {
                @Override
                public void onThrowable(Throwable t) {
                    // Recorded, not asserted: NettyResponseFuture.abort swallows anything thrown here.
                    thrown.set(t);
                    latch.countDown();
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart e) throws Exception {
                    if (e.length() != 0) {
                        String s = new String(e.getBodyPartBytes());
                        logger.info("got part: {}", s);
                        queue.put(s);
                        firstPartReceived.countDown();
                    }
                    return State.CONTINUE;
                }

                @Override
                public State onStatusReceived(HttpResponseStatus e) {
                    status.set(true);
                    return State.CONTINUE;
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

            // The latch also fires on failure, so check for one before looking at the parts.
            assertTrue(latch.await(TIMEOUT, TimeUnit.SECONDS), () -> "Latch failed. Received so far: " + queue);
            assertNull(thrown.get(), () -> "Got throwable: " + thrown.get());
            assertFalse(handshakeTimedOut, "the server gave up waiting for the client to receive part1");
            assertEquals(2, queue.size());
            assertEquals("part1", queue.poll());
            assertEquals("part2", queue.poll());
            assertTrue(status.get());
            assertEquals(1, headers.get());
        }
    }
}
