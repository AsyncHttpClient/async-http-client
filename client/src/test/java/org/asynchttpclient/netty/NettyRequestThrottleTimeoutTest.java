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
package org.asynchttpclient.netty;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NettyRequestThrottleTimeoutTest extends AbstractBasicTest {
    private static final String MSG = "Enough is enough.";
    private static final int SLEEPTIME_MS = 1000;

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new SlowHandler();
    }

    @Test
    public void testRequestTimeout() throws IOException {
        final Semaphore requestThrottle = new Semaphore(1);
        final int samples = 10;

        try (AsyncHttpClient client = asyncHttpClient(config().setMaxConnections(1))) {
            final CountDownLatch latch = new CountDownLatch(samples);
            final List<Exception> tooManyConnections = Collections.synchronizedList(new ArrayList<>(2));

            for (int i = 0; i < samples; i++) {
                new Thread(() -> {
                    try {
                        requestThrottle.acquire();
                        Future<Response> responseFuture = null;
                        try {
                            responseFuture = client.prepareGet(getTargetUrl()).setRequestTimeout(SLEEPTIME_MS / 2)
                                    .execute(new AsyncCompletionHandler<Response>() {

                                        @Override
                                        public Response onCompleted(Response response) {
                                            return response;
                                        }

                                        @Override
                                        public void onThrowable(Throwable t) {
                                            logger.error("onThrowable got an error", t);
                                            try {
                                                Thread.sleep(100);
                                            } catch (InterruptedException e) {
                                                //
                                            }
                                            requestThrottle.release();
                                        }
                                    });
                        } catch (Exception e) {
                            tooManyConnections.add(e);
                        }

                        if (responseFuture != null) {
                            responseFuture.get();
                        }
                    } catch (Exception e) {
                        //
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            assertDoesNotThrow(() -> {
                assertTrue(latch.await(30, TimeUnit.SECONDS));
            });

            for (Exception e : tooManyConnections) {
                logger.error("Exception while calling execute", e);
            }

            assertTrue(tooManyConnections.isEmpty(), "Should not have any connection errors where too many connections have been attempted");
        }
    }

    private static class SlowHandler extends AbstractHandler {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException {
            response.setStatus(HttpServletResponse.SC_OK);
            final AsyncContext asyncContext = request.startAsync();
            new Thread(() -> {
                try {
                    Thread.sleep(SLEEPTIME_MS);
                    response.getOutputStream().print(MSG);
                    response.getOutputStream().flush();
                    asyncContext.complete();
                } catch (InterruptedException | IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }).start();
            baseRequest.setHandled(true);
        }
    }
}
