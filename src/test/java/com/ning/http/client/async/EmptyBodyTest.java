/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
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
package com.ning.http.client.async;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;

/**
 * Tests case where response doesn't have body.
 * 
 * @author Hubert Iwaniuk
 */
public abstract class EmptyBodyTest extends AbstractBasicTest {
    private class NoBodyResponseHandler extends AbstractHandler {
        public void handle(String s, Request request, HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {

            if (!req.getMethod().equalsIgnoreCase("PUT")) {
                resp.setStatus(HttpServletResponse.SC_OK);
            } else {
                resp.setStatus(204);
            }
            request.setHandled(true);
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new NoBodyResponseHandler();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testEmptyBody() throws IOException {
        AsyncHttpClient ahc = getAsyncHttpClient(null);
        try {
            final AtomicBoolean err = new AtomicBoolean(false);
            final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>();
            final AtomicBoolean status = new AtomicBoolean(false);
            final AtomicInteger headers = new AtomicInteger(0);
            final CountDownLatch latch = new CountDownLatch(1);
            ahc.executeRequest(ahc.prepareGet(getTargetUrl()).build(), new AsyncHandler<Object>() {
                public void onThrowable(Throwable t) {
                    fail("Got throwable.", t);
                    err.set(true);
                }

                public STATE onBodyPartReceived(HttpResponseBodyPart e) throws Exception {

                    byte[] bytes = e.getBodyPartBytes();

                    if (bytes.length != 0) {
                        String s = new String(bytes);
                        log.info("got part: {}", s);
                        log.warn("Sampling stacktrace.", new Throwable("trace that, we should not get called for empty body."));
                        queue.put(s);
                    }
                    return STATE.CONTINUE;
                }

                public STATE onStatusReceived(HttpResponseStatus e) throws Exception {
                    status.set(true);
                    return AsyncHandler.STATE.CONTINUE;
                }

                public STATE onHeadersReceived(HttpResponseHeaders e) throws Exception {
                    if (headers.incrementAndGet() == 2) {
                        throw new Exception("Analyze this.");
                    }
                    return STATE.CONTINUE;
                }

                public Object onCompleted() throws Exception {
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
            assertEquals(queue.size(), 0);
            assertTrue(status.get());
            assertEquals(headers.get(), 1);
        } finally {
            ahc.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testPutEmptyBody() throws Throwable {
        AsyncHttpClient ahc = getAsyncHttpClient(null);
        try {
            Response response = ahc.preparePut(getTargetUrl()).setBody("String").execute().get();

            assertNotNull(response);
            assertEquals(response.getStatusCode(), 204);
            assertEquals(response.getResponseBody(), "");
            assertTrue(response.getResponseBodyAsStream() instanceof InputStream);
            assertEquals(response.getResponseBodyAsStream().read(), -1);

        } finally {
            ahc.close();
        }
    }
}
