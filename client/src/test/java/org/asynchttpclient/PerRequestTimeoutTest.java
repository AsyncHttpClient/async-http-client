/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
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
package org.asynchttpclient;

import io.github.artsok.RepeatedIfExceptionsTest;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Per request timeout configuration test.
 *
 * @author Hubert Iwaniuk
 */
public class PerRequestTimeoutTest extends AbstractBasicTest {
    private static final String MSG = "Enough is enough.";

    private static void checkTimeoutMessage(String message, boolean requestTimeout) {
        if (requestTimeout) {
            assertTrue(message.startsWith("Request timeout"), "error message indicates reason of error but got: " + message);
        } else {
            assertTrue(message.startsWith("Read timeout"), "error message indicates reason of error but got: " + message);
        }
        assertTrue(message.contains("localhost"), "error message contains remote host address but got: " + message);
        assertTrue(message.contains("after 100 ms"), "error message contains timeout configuration value but got: " + message);
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new SlowHandler();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testRequestTimeout() throws IOException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> responseFuture = client.prepareGet(getTargetUrl())
                    .setRequestTimeout(Duration.ofMillis(100))
                    .execute();
            Response response = responseFuture.get(2000, TimeUnit.MILLISECONDS);
            assertNull(response);
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
            checkTimeoutMessage(e.getCause().getMessage(), true);
        } catch (TimeoutException e) {
            fail("Timeout.", e);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testReadTimeout() throws IOException {
        try (AsyncHttpClient client = asyncHttpClient(config().setReadTimeout(Duration.ofMillis(100)))) {
            Future<Response> responseFuture = client.prepareGet(getTargetUrl()).execute();
            Response response = responseFuture.get(2000, TimeUnit.MILLISECONDS);
            assertNull(response);
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
            checkTimeoutMessage(e.getCause().getMessage(), false);
        } catch (TimeoutException e) {
            fail("Timeout.", e);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testGlobalDefaultPerRequestInfiniteTimeout() throws IOException {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(Duration.ofMillis(100)))) {
            Future<Response> responseFuture = client.prepareGet(getTargetUrl())
                    .setRequestTimeout(Duration.ofMillis(-1))
                    .execute();
            Response response = responseFuture.get();
            assertNotNull(response);
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
            checkTimeoutMessage(e.getCause().getMessage(), true);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testGlobalRequestTimeout() throws IOException {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(Duration.ofMillis(100)))) {
            Future<Response> responseFuture = client.prepareGet(getTargetUrl()).execute();
            Response response = responseFuture.get(2000, TimeUnit.MILLISECONDS);
            assertNull(response);
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
            checkTimeoutMessage(e.getCause().getMessage(), true);
        } catch (TimeoutException e) {
            fail("Timeout.", e);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testGlobalIdleTimeout() throws IOException {
        final long[] times = {-1, -1};

        try (AsyncHttpClient client = asyncHttpClient(config().setPooledConnectionIdleTimeout(2000))) {
            Future<Response> responseFuture = client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandler<Response>() {
                @Override
                public Response onCompleted(Response response) {
                    return response;
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    times[0] = unpreciseMillisTime();
                    return super.onBodyPartReceived(content);
                }

                @Override
                public void onThrowable(Throwable t) {
                    times[1] = unpreciseMillisTime();
                    super.onThrowable(t);
                }
            });
            Response response = responseFuture.get();
            assertNotNull(response);
            assertEquals(response.getResponseBody(), MSG + MSG);
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        } catch (ExecutionException e) {
            logger.info(String.format("\n@%dms Last body part received\n@%dms Connection killed\n %dms difference.", times[0], times[1], times[1] - times[0]));
            fail("Timeouted on idle.", e);
        }
    }

    private static class SlowHandler extends AbstractHandler {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException {
            response.setStatus(HttpServletResponse.SC_OK);
            final AsyncContext asyncContext = request.startAsync();
            new Thread(() -> {
                try {
                    Thread.sleep(1500);
                    response.getOutputStream().print(MSG);
                    response.getOutputStream().flush();
                } catch (InterruptedException | IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }).start();
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
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
