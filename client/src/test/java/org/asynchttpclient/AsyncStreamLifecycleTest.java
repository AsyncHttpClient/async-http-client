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

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.handler.codec.http.HttpHeaders;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterAll;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests default asynchronous life cycle.
 *
 * @author Hubert Iwaniuk
 */
public class AsyncStreamLifecycleTest extends AbstractBasicTest {
    private static final ExecutorService executorService = Executors.newFixedThreadPool(2);

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
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        logger.error("Failed to sleep for 100 ms.", e);
                    }
                    logger.info("Delivering part1.");
                    writer.write("part1");
                    writer.flush();
                });
                executorService.submit(() -> {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        logger.error("Failed to sleep for 200 ms.", e);
                    }
                    logger.info("Delivering part2.");
                    writer.write("part2");
                    writer.flush();
                    asyncContext.complete();
                });
                request.setHandled(true);
            }
        };
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testStream() throws Exception {
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
                    if (e.length() != 0) {
                        String s = new String(e.getBodyPartBytes());
                        logger.info("got part: {}", s);
                        queue.put(s);
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

            assertTrue(latch.await(1, TimeUnit.SECONDS), "Latch failed.");
            assertFalse(err.get());
            assertEquals(queue.size(), 2);
            assertTrue(queue.contains("part1"));
            assertTrue(queue.contains("part2"));
            assertTrue(status.get());
            assertEquals(headers.get(), 1);
        }
    }
}
