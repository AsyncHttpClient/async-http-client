/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.request;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.asynchttpclient.exception.TooManyConnectionsException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request that cannot take a connection permit only defers on an HTTP/2 connection that could exist. An
 * HTTP/2 connection is registered from ALPN on a secured origin or from an h2c upgrade on a cleartext one,
 * so for a cleartext origin with h2c disabled, which is the default, nothing ever registers and the waiter can
 * only expire. Arming it there holds the request for the whole {@code connectTimeout} before failing it
 * with the permit exception it already had, overriding the {@code acquireFreeChannelTimeout} the caller
 * asked for (0 by default: fail fast).
 */
public class Http2ConnectionWaiterGateTest extends AbstractBasicTest {

    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new BlockingHandler();
    }

    @Test
    public void permitExhaustedOnCleartextOriginFailsWithoutWaitingOutConnectTimeout() throws Exception {
        // connectTimeout bounds the HTTP/2 waiter, so make it far longer than the window asserted below: a
        // regression then fails on the window rather than passing by being merely slow.
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setMaxConnections(1)
                .setConnectTimeout(Duration.ofSeconds(30)))) {

            // execute() takes the permit on this thread before it returns, and the handler holds the
            // exchange open, so the second request below is guaranteed to find the semaphore exhausted.
            Future<Response> holder = client.prepareGet(getTargetUrl()).execute();

            try {
                Future<Response> refused = client.prepareGet(getTargetUrl()).execute();

                // The point of the fix: the refusal is synchronous, so the future is already done by the
                // time execute() hands it back. Without this we would only be asserting on the clock, and a
                // regression would still end in TooManyConnectionsException, just 30 seconds later.
                assertTrue(refused.isDone(), "the permit refusal should fail the future before execute() returns");

                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> refused.get(5, TimeUnit.SECONDS));
                assertInstanceOf(TooManyConnectionsException.class, failure.getCause());
            } finally {
                // In a finally so a failed assertion does not leave the handler parked until the server
                // stops, which turns one red test into a slow and confusing teardown.
                release.countDown();
            }
            holder.get(TIMEOUT, TimeUnit.SECONDS);
        }
    }

    private class BlockingHandler extends AbstractHandler {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServletException(e);
            }
            response.setStatus(HttpServletResponse.SC_OK);
            baseRequest.setHandled(true);
        }
    }
}
