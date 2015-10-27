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
package org.asynchttpclient;

import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET;
import static org.testng.Assert.*;
import io.netty.handler.codec.http.HttpHeaders;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;

public class AsyncStreamHandlerTest extends AbstractBasicTest {

    private static final String RESPONSE = "param_1_";

    @Test(groups = "standalone")
    public void asyncStreamGETTest() throws Exception {
        final CountDownLatch l = new CountDownLatch(1);
        final AtomicReference<HttpHeaders> responseHeaders = new AtomicReference<>();
        final AtomicReference<Throwable> throwable = new AtomicReference<>();
        try (AsyncHttpClient c = asyncHttpClient()) {
            c.prepareGet(getTargetUrl()).execute(new AsyncHandlerAdapter() {

                @Override
                public State onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    try {
                        responseHeaders.set(content.getHeaders());
                        return State.ABORT;
                    } finally {
                        l.countDown();
                    }
                }

                @Override
                public void onThrowable(Throwable t) {
                    try {
                        throwable.set(t);
                    } finally {
                        l.countDown();
                    }
                }
            });

            if (!l.await(5, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
            
            HttpHeaders h = responseHeaders.get();
            assertNotNull(h, "No response headers");
            assertEquals(h.get(HttpHeaders.Names.CONTENT_TYPE), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET, "Unexpected content-type");
            assertNull(throwable.get(), "Unexpected exception");
        }
    }

    @Test(groups = "standalone")
    public void asyncStreamPOSTTest() throws Exception {

        final AtomicReference<HttpHeaders> responseHeaders = new AtomicReference<>();

        try (AsyncHttpClient c = asyncHttpClient()) {
            Future<String> f = c.preparePost(getTargetUrl())//
                    .setHeader("Content-Type", "application/x-www-form-urlencoded")//
                    .addFormParam("param_1", "value_1")//
                    .execute(new AsyncHandlerAdapter() {
                private StringBuilder builder = new StringBuilder();

                @Override
                public State onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    responseHeaders.set(content.getHeaders());
                    return State.CONTINUE;
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    builder.append(new String(content.getBodyPartBytes()));
                    return State.CONTINUE;
                }

                @Override
                public String onCompleted() throws Exception {
                    return builder.toString().trim();
                }
            });

            String responseBody = f.get(10, TimeUnit.SECONDS);
            HttpHeaders h = responseHeaders.get();
            assertNotNull(h);
            assertEquals(h.get(HttpHeaders.Names.CONTENT_TYPE), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
            assertEquals(responseBody, RESPONSE);
        }
    }

    @Test(groups = "standalone")
    public void asyncStreamInterruptTest() throws Exception {
        final CountDownLatch l = new CountDownLatch(1);
        
        final AtomicReference<HttpHeaders> responseHeaders = new AtomicReference<>();
        final AtomicBoolean bodyReceived = new AtomicBoolean(false);
        final AtomicReference<Throwable> throwable = new AtomicReference<>();
        try (AsyncHttpClient c = asyncHttpClient()) {
            c.preparePost(getTargetUrl())//
            .setHeader("Content-Type", "application/x-www-form-urlencoded")//
            .addFormParam("param_1", "value_1")//
            .execute(new AsyncHandlerAdapter() {

                @Override
                public State onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    responseHeaders.set(content.getHeaders());
                    return State.ABORT;
                }

                @Override
                public State onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
                    bodyReceived.set(true);
                    return State.ABORT;
                }

                @Override
                public void onThrowable(Throwable t) {
                    throwable.set(t);
                    l.countDown();
                }
            });

            l.await(5, TimeUnit.SECONDS);
            assertTrue(!bodyReceived.get(), "Interrupted not working");
            HttpHeaders h = responseHeaders.get();
            assertNotNull(h, "Should receive non null headers");
            assertEquals(h.get(HttpHeaders.Names.CONTENT_TYPE).toLowerCase(Locale.ENGLISH), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET.toLowerCase(Locale.ENGLISH), "Unexpected content-type");
            assertNull(throwable.get(), "Should get an exception");
        }
    }

    @Test(groups = "standalone")
    public void asyncStreamFutureTest() throws Exception {
        final AtomicReference<HttpHeaders> responseHeaders = new AtomicReference<>();
        final AtomicReference<Throwable> throwable = new AtomicReference<>();
        try (AsyncHttpClient c = asyncHttpClient()) {
            Future<String> f = c.preparePost(getTargetUrl()).addFormParam("param_1", "value_1").execute(new AsyncHandlerAdapter() {
                private StringBuilder builder = new StringBuilder();

                @Override
                public State onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    responseHeaders.set(content.getHeaders());
                    return State.CONTINUE;
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    builder.append(new String(content.getBodyPartBytes()));
                    return State.CONTINUE;
                }

                @Override
                public String onCompleted() throws Exception {
                    return builder.toString().trim();
                }

                @Override
                public void onThrowable(Throwable t) {
                    throwable.set(t);
                }
            });

            String responseBody = f.get(5, TimeUnit.SECONDS);
            HttpHeaders h = responseHeaders.get();
            assertNotNull(h, "Should receive non null headers");
            assertEquals(h.get(HttpHeaders.Names.CONTENT_TYPE).toLowerCase(Locale.ENGLISH), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET.toLowerCase(Locale.ENGLISH), "Unexpected content-type");
            assertNotNull(responseBody, "No response body");
            assertEquals(responseBody.trim(), RESPONSE, "Unexpected response body");
            assertNull(throwable.get(), "Unexpected exception");
        }
    }

    @Test(groups = "standalone")
    public void asyncStreamThrowableRefusedTest() throws Exception {

        final CountDownLatch l = new CountDownLatch(1);
        try (AsyncHttpClient c = asyncHttpClient()) {
            c.prepareGet(getTargetUrl()).execute(new AsyncHandlerAdapter() {

                @Override
                public State onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    throw new RuntimeException("FOO");
                }

                @Override
                public void onThrowable(Throwable t) {
                    try {
                        if (t.getMessage() != null) {
                            assertEquals(t.getMessage(), "FOO");
                        }
                    } finally {
                        l.countDown();
                    }
                }
            });

            if (!l.await(10, TimeUnit.SECONDS)) {
                fail("Timed out");
            }
        }
    }

    @Test(groups = "standalone")
    public void asyncStreamReusePOSTTest() throws Exception {

        final AtomicReference<HttpHeaders> responseHeaders = new AtomicReference<>();
        try (AsyncHttpClient c = asyncHttpClient()) {
            BoundRequestBuilder rb = c.preparePost(getTargetUrl())//
                    .setHeader("Content-Type", "application/x-www-form-urlencoded")
                    .addFormParam("param_1", "value_1");
            
            Future<String> f = rb.execute(new AsyncHandlerAdapter() {
                private StringBuilder builder = new StringBuilder();

                @Override
                public State onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    responseHeaders.set(content.getHeaders());
                    return State.CONTINUE;
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    builder.append(new String(content.getBodyPartBytes()));
                    return State.CONTINUE;
                }

                @Override
                public String onCompleted() throws Exception {
                    return builder.toString();
                }
            });

            String r = f.get(5, TimeUnit.SECONDS);
            HttpHeaders h = responseHeaders.get();
            assertNotNull(h, "Should receive non null headers");
            assertEquals(h.get(HttpHeaders.Names.CONTENT_TYPE).toLowerCase(Locale.ENGLISH), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET.toLowerCase(Locale.ENGLISH), "Unexpected content-type");
            assertNotNull(r, "No response body");
            assertEquals(r.trim(), RESPONSE, "Unexpected response body");
            
            responseHeaders.set(null);

            // Let do the same again
            f = rb.execute(new AsyncHandlerAdapter() {
                private StringBuilder builder = new StringBuilder();

                @Override
                public State onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    responseHeaders.set(content.getHeaders());
                    return State.CONTINUE;
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    builder.append(new String(content.getBodyPartBytes()));
                    return State.CONTINUE;
                }

                @Override
                public String onCompleted() throws Exception {
                    return builder.toString();
                }
            });

            f.get(5, TimeUnit.SECONDS);
            h = responseHeaders.get();
            assertNotNull(h, "Should receive non null headers");
            assertEquals(h.get(HttpHeaders.Names.CONTENT_TYPE).toLowerCase(Locale.ENGLISH), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET.toLowerCase(Locale.ENGLISH), "Unexpected content-type");
            assertNotNull(r, "No response body");
            assertEquals(r.trim(), RESPONSE, "Unexpected response body");
        }
    }

    @Test(groups = "online")
    public void asyncStream302RedirectWithBody() throws Exception {
        final AtomicReference<Integer> statusCode = new AtomicReference<>(0);
        final AtomicReference<HttpHeaders> responseHeaders = new AtomicReference<>();
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            Future<String> f = c.prepareGet("http://google.com/").execute(new AsyncHandlerAdapter() {

                public State onStatusReceived(HttpResponseStatus status) throws Exception {
                    statusCode.set(status.getStatusCode());
                    return State.CONTINUE;
                }

                @Override
                public State onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    responseHeaders.set(content.getHeaders());
                    return State.CONTINUE;
                }

                @Override
                public String onCompleted() throws Exception {
                    return null;
                }
            });

            f.get(20, TimeUnit.SECONDS);
            assertTrue(statusCode.get() != 302);
            HttpHeaders h = responseHeaders.get();
            assertNotNull(h);
            assertEquals(h.get("server"), "gws");
            // This assertion below is not an invariant, since implicitly contains locale-dependant settings
            // and fails when run in country having own localized Google site and it's locale relies on something
            // other than ISO-8859-1.
            // In Hungary for example, http://google.com/ redirects to http://www.google.hu/, a localized
            // Google site, that uses ISO-8892-2 encoding (default for HU). Similar is true for other
            // non-ISO-8859-1 using countries that have "localized" google, like google.hr, google.rs, google.cz, google.sk etc.
            //
            // assertEquals(h.get(HttpHeaders.Names.CONTENT_TYPE), "text/html; charset=ISO-8859-1");
        }
    }

    @Test(groups = "standalone", timeOut = 3000, description = "Test behavior of 'read only status line' scenario.")
    public void asyncStreamJustStatusLine() throws Exception {
        final int STATUS = 0;
        final int COMPLETED = 1;
        final int OTHER = 2;
        final boolean[] whatCalled = new boolean[] { false, false, false };
        final CountDownLatch latch = new CountDownLatch(1);
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Integer> statusCode = client.prepareGet(getTargetUrl()).execute(new AsyncHandler<Integer>() {
                private int status = -1;

                @Override
                public void onThrowable(Throwable t) {
                    whatCalled[OTHER] = true;
                    latch.countDown();
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                    whatCalled[OTHER] = true;
                    latch.countDown();
                    return State.ABORT;
                }

                @Override
                public State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                    whatCalled[STATUS] = true;
                    status = responseStatus.getStatusCode();
                    latch.countDown();
                    return State.ABORT;
                }

                @Override
                public State onHeadersReceived(HttpResponseHeaders headers) throws Exception {
                    whatCalled[OTHER] = true;
                    latch.countDown();
                    return State.ABORT;
                }

                @Override
                public Integer onCompleted() throws Exception {
                    whatCalled[COMPLETED] = true;
                    latch.countDown();
                    return status;
                }
            });

            if (!latch.await(2, TimeUnit.SECONDS)) {
                fail("Timeout");
                return;
            }
            Integer status = statusCode.get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals((int) status, 200, "Expected status code failed.");

            if (!whatCalled[STATUS]) {
                fail("onStatusReceived not called.");
            }
            if (!whatCalled[COMPLETED]) {
                fail("onCompleted not called.");
            }
            if (whatCalled[OTHER]) {
                fail("Other method of AsyncHandler got called.");
            }
        }
    }

    @Test(groups = "online")
    public void asyncOptionsTest() throws Exception {
        final AtomicReference<HttpHeaders> responseHeaders = new AtomicReference<>();

        try (AsyncHttpClient c = asyncHttpClient()) {
            final String[] expected = { "GET", "HEAD", "OPTIONS", "POST", "TRACE" };
            Future<String> f = c.prepareOptions("http://www.apache.org/").execute(new AsyncHandlerAdapter() {

                @Override
                public State onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    responseHeaders.set(content.getHeaders());
                    return State.ABORT;
                }

                @Override
                public String onCompleted() throws Exception {
                    return "OK";
                }
            });

            f.get(20, TimeUnit.SECONDS) ;
            HttpHeaders h = responseHeaders.get();
            assertNotNull(h);
            String[] values = h.get(HttpHeaders.Names.ALLOW).split(",|, ");
            assertNotNull(values);
            assertEquals(values.length, expected.length);
            Arrays.sort(values);
            assertEquals(values, expected);
        }
    }

    @Test(groups = "standalone")
    public void closeConnectionTest() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            Response r = c.prepareGet(getTargetUrl()).execute(new AsyncHandler<Response>() {

                private Response.ResponseBuilder builder = new Response.ResponseBuilder();

                public State onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    builder.accumulate(content);
                    return State.CONTINUE;
                }

                public void onThrowable(Throwable t) {
                }

                public State onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    builder.accumulate(content);
                    return content.isLast() ? State.ABORT : State.CONTINUE;
                }

                public State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                    builder.accumulate(responseStatus);

                    return State.CONTINUE;
                }

                public Response onCompleted() throws Exception {
                    return builder.build();
                }
            }).get();

            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
        }
    }
}
