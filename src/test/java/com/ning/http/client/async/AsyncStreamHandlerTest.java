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

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AsyncStreamHandlerTest extends AbstractBasicTest {
    private final static String RESPONSE = "param_1_";
    private final static String UTF8 = "text/html;charset=utf-8";

    @Test(groups = { "standalone", "default_provider" })
    public void asyncStreamGETTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);

            client.prepareGet(getTargetUrl()).execute(new AsyncHandlerAdapter() {

                @Override
                public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    try {
                        FluentCaseInsensitiveStringsMap h = content.getHeaders();
                        Assert.assertNotNull(h);
                        Assert.assertEquals(h.getJoinedValue("content-type", ", ").toLowerCase(Locale.ENGLISH), UTF8);
                        return STATE.ABORT;
                    } finally {
                        l.countDown();
                    }
                }

                @Override
                public void onThrowable(Throwable t) {
                    try {
                        Assert.fail("", t);
                    } finally {
                        l.countDown();
                    }
                }
            });

            if (!l.await(5, TimeUnit.SECONDS)) {
                Assert.fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void asyncStreamPOSTTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            Map<String, Collection<String>> m = new HashMap<String, Collection<String>>();
            m.put("param_1", Arrays.asList("value_1"));

            client.preparePost(getTargetUrl()).setParameters(m).execute(new AsyncHandlerAdapter() {
                private StringBuilder builder = new StringBuilder();

                @Override
                public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    FluentCaseInsensitiveStringsMap h = content.getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getJoinedValue("content-type", ", ").toLowerCase(Locale.ENGLISH), UTF8);
                    return STATE.CONTINUE;
                }

                @Override
                public STATE onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    builder.append(new String(content.getBodyPartBytes()));
                    return STATE.CONTINUE;
                }

                @Override
                public String onCompleted() throws Exception {
                    try {
                        String r = builder.toString().trim();
                        Assert.assertEquals(r, RESPONSE);
                        return r;
                    } finally {
                        l.countDown();
                    }
                }
            });

            if (!l.await(10, TimeUnit.SECONDS)) {
                Assert.fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void asyncStreamInterruptTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");

            Map<String, Collection<String>> m = new HashMap<String, Collection<String>>();
            m.put("param_1", Arrays.asList("value_1"));

            final AtomicBoolean a = new AtomicBoolean(true);

            client.preparePost(getTargetUrl()).setParameters(m).execute(new AsyncHandlerAdapter() {

                @Override
                public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    FluentCaseInsensitiveStringsMap h = content.getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getJoinedValue("content-type", ", ").toLowerCase(Locale.ENGLISH), UTF8);
                    return STATE.ABORT;
                }

                @Override
                public STATE onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
                    a.set(false);
                    Assert.fail("Interrupted not working");
                    return STATE.ABORT;
                }

                @Override
                public void onThrowable(Throwable t) {
                    try {
                        Assert.fail("", t);
                    } finally {
                        l.countDown();
                    }
                }
            });

            l.await(5, TimeUnit.SECONDS);
            Assert.assertTrue(a.get());
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void asyncStreamFutureTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Map<String, Collection<String>> m = new HashMap<String, Collection<String>>();
            m.put("param_1", Arrays.asList("value_1"));

            Future<String> f = client.preparePost(getTargetUrl()).setParameters(m).execute(new AsyncHandlerAdapter() {
                private StringBuilder builder = new StringBuilder();

                @Override
                public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    FluentCaseInsensitiveStringsMap h = content.getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getJoinedValue("content-type", ", ").toLowerCase(Locale.ENGLISH), UTF8);
                    return STATE.CONTINUE;
                }

                @Override
                public STATE onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    builder.append(new String(content.getBodyPartBytes()));
                    return STATE.CONTINUE;
                }

                @Override
                public String onCompleted() throws Exception {
                    String r = builder.toString().trim();
                    Assert.assertEquals(r, RESPONSE);
                    return r;
                }

                @Override
                public void onThrowable(Throwable t) {
                    Assert.fail("", t);
                }
            });

            try {
                String r = f.get(5, TimeUnit.SECONDS);
                Assert.assertNotNull(r);
                Assert.assertEquals(r.trim(), RESPONSE);
            } catch (TimeoutException ex) {
                Assert.fail();
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void asyncStreamThrowableRefusedTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);

            client.prepareGet(getTargetUrl()).execute(new AsyncHandlerAdapter() {

                @Override
                public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    throw new RuntimeException("FOO");
                }

                @Override
                public void onThrowable(Throwable t) {
                    try {
                        if (t.getMessage() != null) {
                            Assert.assertEquals(t.getMessage(), "FOO");
                        }
                    } finally {
                        l.countDown();
                    }
                }
            });

            if (!l.await(10, TimeUnit.SECONDS)) {
                Assert.fail("Timed out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void asyncStreamReusePOSTTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");

            Map<String, Collection<String>> m = new HashMap<String, Collection<String>>();
            m.put("param_1", Arrays.asList("value_1"));

            client.preparePost(getTargetUrl()).setParameters(m).execute(new AsyncHandlerAdapter() {
                private StringBuilder builder = new StringBuilder();

                @Override
                public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    FluentCaseInsensitiveStringsMap h = content.getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getJoinedValue("content-type", ", ").toLowerCase(Locale.ENGLISH), UTF8);
                    return STATE.CONTINUE;
                }

                @Override
                public STATE onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    builder.append(new String(content.getBodyPartBytes()));
                    return STATE.CONTINUE;
                }

                @Override
                public String onCompleted() throws Exception {
                    try {
                        String r = builder.toString().trim();
                        Assert.assertEquals(r, RESPONSE);
                        return r;
                    } finally {
                        l.countDown();
                    }

                }
            });

            if (!l.await(20, TimeUnit.SECONDS)) {
                Assert.fail("Timeout out");
            }

            // Let do the same again
            client.preparePost(getTargetUrl()).setParameters(m).execute(new AsyncHandlerAdapter() {
                private StringBuilder builder = new StringBuilder();

                @Override
                public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    FluentCaseInsensitiveStringsMap h = content.getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getJoinedValue("content-type", ", ").toLowerCase(Locale.ENGLISH), UTF8);
                    return STATE.CONTINUE;
                }

                @Override
                public STATE onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    builder.append(new String(content.getBodyPartBytes()));
                    return STATE.CONTINUE;
                }

                @Override
                public String onCompleted() throws Exception {
                    try {
                        String r = builder.toString().trim();
                        Assert.assertEquals(r, RESPONSE);
                        return r;
                    } finally {
                        l.countDown();
                    }
                }
            });

            if (!l.await(20, TimeUnit.SECONDS)) {
                Assert.fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void asyncStream301WithBody() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            client.prepareGet("http://google.com/").execute(new AsyncHandlerAdapter() {
                private StringBuilder builder = new StringBuilder();

                @Override
                public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    FluentCaseInsensitiveStringsMap h = content.getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getJoinedValue("content-type", ", ").toLowerCase(Locale.ENGLISH), "text/html; charset=utf-8");
                    return STATE.CONTINUE;
                }

                @Override
                public STATE onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    builder.append(new String(content.getBodyPartBytes()));
                    return STATE.CONTINUE;
                }

                @Override
                public String onCompleted() throws Exception {
                    String r = builder.toString();
                    Assert.assertTrue(r.contains("301 Moved"));
                    l.countDown();
                    return r;
                }
            });

            if (!l.await(20, TimeUnit.SECONDS)) {
                Assert.fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void asyncStream301RedirectWithBody() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
        try {
            final CountDownLatch l = new CountDownLatch(1);
            client.prepareGet("http://google.com/").execute(new AsyncHandlerAdapter() {
                private StringBuilder builder = new StringBuilder();

                @Override
                public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    FluentCaseInsensitiveStringsMap h = content.getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getFirstValue("server"), "gws");
                    // This assertion below is not an invariant, since implicitly contains locale-dependant settings
                    // and fails when run in country having own localized Google site and it's locale relies on something
                    // other than ISO-8859-1.
                    // In Hungary for example, http://google.com/ redirects to http://www.google.hu/, a localized
                    // Google site, that uses ISO-8892-2 encoding (default for HU). Similar is true for other
                    // non-ISO-8859-1 using countries that have "localized" google, like google.hr, google.rs, google.cz, google.sk etc.
                    //
                    // Assert.assertEquals(h.getJoinedValue("content-type", ", "), "text/html; charset=ISO-8859-1");
                    return STATE.CONTINUE;
                }

                @Override
                public STATE onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    builder.append(new String(content.getBodyPartBytes()));
                    return STATE.CONTINUE;
                }

                @Override
                public String onCompleted() throws Exception {
                    String r = builder.toString();
                    Assert.assertTrue(!r.contains("301 Moved"));
                    l.countDown();

                    return r;
                }
            });

            if (!l.await(20, TimeUnit.SECONDS)) {
                Assert.fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" }, timeOut = 3000, description = "Test behavior of 'read only status line' scenario.")
    public void asyncStreamJustStatusLine() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final int STATUS = 0;
            final int COMPLETED = 1;
            final int OTHER = 2;
            final boolean[] whatCalled = new boolean[] { false, false, false };
            final CountDownLatch latch = new CountDownLatch(1);
            Future<Integer> statusCode = client.prepareGet(getTargetUrl()).execute(new AsyncHandler<Integer>() {
                private int status = -1;

                /* @Override */
                public void onThrowable(Throwable t) {
                    whatCalled[OTHER] = true;
                    latch.countDown();
                }

                /* @Override */
                public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                    whatCalled[OTHER] = true;
                    latch.countDown();
                    return STATE.ABORT;
                }

                /* @Override */
                public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                    whatCalled[STATUS] = true;
                    System.out.println(responseStatus);
                    status = responseStatus.getStatusCode();
                    latch.countDown();
                    return STATE.ABORT;
                }

                /* @Override */
                public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
                    whatCalled[OTHER] = true;
                    latch.countDown();
                    return STATE.ABORT;
                }

                /* @Override */
                public Integer onCompleted() throws Exception {
                    whatCalled[COMPLETED] = true;
                    latch.countDown();
                    return status;
                }
            });

            if (!latch.await(2, TimeUnit.SECONDS)) {
                Assert.fail("Timeout");
                return;
            }
            Integer status = statusCode.get(TIMEOUT, TimeUnit.SECONDS);
            Assert.assertEquals((int) status, 200, "Expected status code failed.");

            if (!whatCalled[STATUS]) {
                Assert.fail("onStatusReceived not called.");
            }
            if (!whatCalled[COMPLETED]) {
                Assert.fail("onCompleted not called.");
            }
            if (whatCalled[OTHER]) {
                Assert.fail("Other method of AsyncHandler got called.");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void asyncOptionsTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            client.prepareOptions("http://www.apache.org/").execute(new AsyncHandlerAdapter() {

                @Override
                public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    FluentCaseInsensitiveStringsMap h = content.getHeaders();
                    Assert.assertNotNull(h);
                    String result = h.getJoinedValue("Allow", ", ");
                    String[] resultParts = result.split(",");
                    String[] expected = "OPTIONS,GET,HEAD,POST,TRACE".split(",");
                    Arrays.sort(resultParts);
                    Arrays.sort(expected);
                    Assert.assertEquals(expected, resultParts);
                    return STATE.ABORT;
                }

                @Override
                public STATE onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    return STATE.CONTINUE;
                }

                @Override
                public String onCompleted() throws Exception {
                    try {
                        return "OK";
                    } finally {
                        l.countDown();
                    }
                }
            });

            if (!l.await(20, TimeUnit.SECONDS)) {
                Assert.fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void closeConnectionTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response r = client.prepareGet(getTargetUrl()).execute(new AsyncHandler<Response>() {

                private Response.ResponseBuilder builder = new Response.ResponseBuilder();

                public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                    builder.accumulate(content);
                    return STATE.CONTINUE;
                }

                public void onThrowable(Throwable t) {
                }

                public STATE onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                    builder.accumulate(content);

                    if (content.isLast()) {
                        content.markUnderlyingConnectionAsClosed();
                    }
                    return STATE.CONTINUE;
                }

                public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                    builder.accumulate(responseStatus);

                    return STATE.CONTINUE;
                }

                public Response onCompleted() throws Exception {
                    return builder.build();
                }
            }).get();

            Assert.assertNotNull(r);
            Assert.assertEquals(r.getStatusCode(), 200);
        } finally {
            client.close();
        }
    }
}
