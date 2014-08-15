/*
 * Copyright (c) 2012-2013 Sonatype, Inc. All rights reserved.
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

package org.asynchttpclient.providers.grizzly;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.fail;

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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class GrizzlyUnexpectingTimeoutTest extends AbstractBasicTest {

    private static final String MSG = "Unauthorized without WWW-Authenticate header";

    protected String getExpectedTimeoutMessage() {
        return "401 response received, but no WWW-Authenticate header was present";
    }

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return GrizzlyProviderUtil.grizzlyProvider(config);
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ExpectExceptionHandler();
    }

    private class ExpectExceptionHandler extends AbstractHandler {
        public void handle(String target, Request baseRequest, HttpServletRequest request, final HttpServletResponse response)
                throws IOException, ServletException {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            final Continuation continuation = ContinuationSupport.getContinuation(request);
            continuation.suspend();
            new Thread(new Runnable() {
                public void run() {
                    try {
                        response.getOutputStream().print(MSG);
                        response.getOutputStream().flush();
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }).start();
            baseRequest.setHandled(true);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void unexpectingTimeoutTest() throws IOException {
        final AtomicInteger counts = new AtomicInteger();
        final int timeout = 100;

        final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeout(timeout).build());
        try {
            Future<Response> responseFuture = client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandler<Response>() {
                @Override
                public Response onCompleted(Response response) throws Exception {
                    counts.incrementAndGet();
                    return response;
                }

                @Override
                public void onThrowable(Throwable t) {
                    counts.incrementAndGet();
                    super.onThrowable(t);
                }
            });
            // currently, an exception is expected
            // because the grizzly provider would throw IllegalStateException if WWW-Authenticate header doesn't exist with 401 response status.
            try {
                Response response = responseFuture.get();
                assertNull(response);
            } catch (InterruptedException e) {
                fail("Interrupted.", e);
            } catch (ExecutionException e) {
                assertFalse(e.getCause() instanceof TimeoutException);
                assertEquals(e.getCause().getMessage(), getExpectedTimeoutMessage());
            }
            // wait for timeout again.
            try {
                Thread.sleep(timeout * 2);
            } catch (InterruptedException e) {
                fail("Interrupted.", e);
            }
            // the result should be either onCompleted or onThrowable.
            assertEquals(counts.get(), 1, "result should be one");
        } finally {
            client.close();
        }
    }
}
