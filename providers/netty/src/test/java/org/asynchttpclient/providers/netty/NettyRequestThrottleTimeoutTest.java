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
package org.asynchttpclient.providers.netty;

import static org.testng.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.asynchttpclient.async.AbstractBasicTest;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

public class NettyRequestThrottleTimeoutTest extends AbstractBasicTest {
    private static final String MSG = "Enough is enough.";
    private static final int SLEEPTIME_MS = 1000;

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new SlowHandler();
    }

    private class SlowHandler extends AbstractHandler {
        public void handle(String target, Request baseRequest, HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException {
            response.setStatus(HttpServletResponse.SC_OK);
            final Continuation continuation = ContinuationSupport.getContinuation(request);
            continuation.suspend();
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(SLEEPTIME_MS);
                        response.getOutputStream().print(MSG);
                        response.getOutputStream().flush();
                        continuation.complete();
                    } catch (InterruptedException e) {
                        logger.error(e.getMessage(), e);
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }).start();
            baseRequest.setHandled(true);
        }
    }

    @Test(groups = { "standalone", "netty_provider" })
    public void testRequestTimeout() throws IOException {
        final Semaphore requestThrottle = new Semaphore(1);

        final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setCompressionEnabled(true).setAllowPoolingConnection(true)
                .setMaximumConnectionsTotal(1).build());
        try {
            final CountDownLatch latch = new CountDownLatch(2);
            final List<Exception> tooManyConnections = Collections.synchronizedList(new ArrayList<Exception>(2));

            for (int i = 0; i < 2; i++) {
                new Thread(new Runnable() {

                    public void run() {
                        try {
                            requestThrottle.acquire();
                            Future<Response> responseFuture = null;
                            try {
                                responseFuture = client.prepareGet(getTargetUrl()).setRequestTimeoutInMs(SLEEPTIME_MS / 2).execute(new AsyncCompletionHandler<Response>() {

                                    @Override
                                    public Response onCompleted(Response response) throws Exception {
                                        requestThrottle.release();
                                        return response;
                                    }

                                    @Override
                                    public void onThrowable(Throwable t) {
                                        requestThrottle.release();
                                    }
                                });
                            } catch (Exception e) {
                                tooManyConnections.add(e);
                            }

                            if (responseFuture != null)
                                responseFuture.get();
                        } catch (Exception e) {
                        } finally {
                            latch.countDown();
                        }

                    }
                }).start();
            }

            try {
                latch.await(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail("failed to wait for requests to complete");
            }

            assertTrue(tooManyConnections.isEmpty(), "Should not have any connection errors where too many connections have been attempted");
        } finally {
            client.close();
        }
    }
}
