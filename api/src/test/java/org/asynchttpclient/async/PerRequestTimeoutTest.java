/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.async;

import static org.asynchttpclient.util.DateUtil.millisTime;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.Response;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Per request timeout configuration test.
 * 
 * @author Hubert Iwaniuk
 */
public abstract class PerRequestTimeoutTest extends AbstractBasicTest {
    private static final String MSG = "Enough is enough.";

    protected abstract void checkTimeoutMessage(String message);

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
                        Thread.sleep(1500);
                        response.getOutputStream().print(MSG);
                        response.getOutputStream().flush();
                    } catch (InterruptedException e) {
                        logger.error(e.getMessage(), e);
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }).start();
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(3000);
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

    @Test(groups = { "standalone", "default_provider" })
    public void testRequestTimeout() throws IOException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Future<Response> responseFuture = client.prepareGet(getTargetUrl()).setRequestTimeoutInMs(100).execute();
            Response response = responseFuture.get(2000, TimeUnit.MILLISECONDS);
            assertNull(response);
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
            checkTimeoutMessage(e.getCause().getMessage());
        } catch (TimeoutException e) {
            fail("Timeout.", e);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testGlobalDefaultPerRequestInfiniteTimeout() throws IOException {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(100).build());
        try {
            Future<Response> responseFuture = client.prepareGet(getTargetUrl()).setRequestTimeoutInMs(-1).execute();
            Response response = responseFuture.get();
            assertNotNull(response);
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
            checkTimeoutMessage(e.getCause().getMessage());
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testGlobalRequestTimeout() throws IOException {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(100).build());
        try {
            Future<Response> responseFuture = client.prepareGet(getTargetUrl()).execute();
            Response response = responseFuture.get(2000, TimeUnit.MILLISECONDS);
            assertNull(response);
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
            checkTimeoutMessage(e.getCause().getMessage());
        } catch (TimeoutException e) {
            fail("Timeout.", e);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testGlobalIdleTimeout() throws IOException {
        final long times[] = new long[] { -1, -1 };

        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setIdleConnectionInPoolTimeoutInMs(2000).build());
        try {
            Future<Response> responseFuture = client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandler<Response>() {
                @Override
                public Response onCompleted(Response response) throws Exception {
                    return response;
                }

                @Override
                public STATE onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    times[0] = millisTime();
                    return super.onBodyPartReceived(content);
                }

                @Override
                public void onThrowable(Throwable t) {
                    times[1] = millisTime();
                    super.onThrowable(t);
                }
            });
            Response response = responseFuture.get();
            assertNotNull(response);
            assertEquals(response.getResponseBody(), MSG + MSG);
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        } catch (ExecutionException e) {
            logger.info(String.format("\n@%dms Last body part received\n@%dms Connection killed\n %dms difference.", times[0], times[1], (times[1] - times[0])));
            fail("Timeouted on idle.", e);
        } finally {
            client.close();
        }
    }
}
