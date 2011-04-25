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
package com.ning.http.client.async;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.PerRequestConfig;
import com.ning.http.client.Response;
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

    protected String getExpectedTimeoutMessage() {
        return "No response received after 100";
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
                        Thread.sleep(1500);
                        response.getOutputStream().print(MSG);
                        response.getOutputStream().flush();
                    } catch (InterruptedException e) {
                        log.error(e.getMessage(), e);
                    } catch (IOException e) {
                        log.error(e.getMessage(), e);
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
                        log.error(e.getMessage(), e);
                    } catch (IOException e) {
                        log.error(e.getMessage(), e);
                    }
                }
            }).start();
            baseRequest.setHandled(true);
        }
    }

    @Test(groups = {"standalone", "default_provider"})
    public void testRequestTimeout() throws IOException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        PerRequestConfig requestConfig = new PerRequestConfig();
        requestConfig.setRequestTimeoutInMs(100);
        Future<Response> responseFuture =
                client.prepareGet(getTargetUrl()).setPerRequestConfig(requestConfig).execute();
        try {
            Response response = responseFuture.get(2000, TimeUnit.MILLISECONDS);
            assertNull(response);
            client.close();
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
            assertEquals(e.getCause().getMessage(), getExpectedTimeoutMessage());
        } catch (TimeoutException e) {
            fail("Timeout.", e);
        }
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void testGlobalDefaultPerRequestInfiniteTimeout() throws IOException {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(100).build());
        PerRequestConfig requestConfig = new PerRequestConfig();
        requestConfig.setRequestTimeoutInMs(-1);
        Future<Response> responseFuture =
                client.prepareGet(getTargetUrl()).setPerRequestConfig(requestConfig).execute();
        try {
            Response response = responseFuture.get();
            assertNotNull(response);
            client.close();
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
            assertEquals(e.getCause().getMessage(), getExpectedTimeoutMessage());
        }
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void testGlobalRequestTimeout() throws IOException {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(100).build());
        Future<Response> responseFuture = client.prepareGet(getTargetUrl()).execute();
        try {
            Response response = responseFuture.get(2000, TimeUnit.MILLISECONDS);
            assertNull(response);
            client.close();
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
            assertEquals(e.getCause().getMessage(), getExpectedTimeoutMessage());
        } catch (TimeoutException e) {
            fail("Timeout.", e);
        }
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void testGlobalIdleTimeout() throws IOException {
        final long times[] = new long[]{-1, -1};

        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setIdleConnectionInPoolTimeoutInMs(2000).build());
        Future<Response> responseFuture = client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandler<Response>() {
            @Override
            public Response onCompleted(Response response) throws Exception {
                return response;
            }

            @Override
            public STATE onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                times[0] = System.currentTimeMillis();
                return super.onBodyPartReceived(content);
            }

            @Override
            public void onThrowable(Throwable t) {
                times[1] = System.currentTimeMillis();
                super.onThrowable(t);
            }
        });
        try {
            Response response = responseFuture.get();
            assertNotNull(response);
            assertEquals(response.getResponseBody(), MSG + MSG);
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        } catch (ExecutionException e) {
            log.info(String.format("\n@%dms Last body part received\n@%dms Connection killed\n %dms difference.",
                    times[0], times[1], (times[1] - times[0])));
            fail("Timeouted on idle.", e);
        }
        client.close();
    }
}
