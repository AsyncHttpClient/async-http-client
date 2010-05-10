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
import com.ning.http.client.Headers;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AsyncStreamHandlerTest extends AbstractBasicTest {

    private final static String RESPONSE = "param_0_param_4_param_1_param_2_param_3_";

    private final static String UTF8 = "text/html; charset=utf-8";

    @Test
    public void asyncStreamGETTest() throws Throwable {
        final CountDownLatch l = new CountDownLatch(1);
        AsyncHttpClient c = new AsyncHttpClient();

        c.prepareGet(TARGET_URL).execute(new AsyncHandlerAdapter() {

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders<String> content) throws Exception {
                try {
                    Headers h = content.getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
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
    }

    @Test
    public void asyncStreamPOSTTest() throws Throwable {
        final CountDownLatch l = new CountDownLatch(1);
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");

        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < 5; i++) {
            m.put("param_" + i, "value_" + i);
        }
        AsyncHttpClient c = new AsyncHttpClient();

        c.preparePost(TARGET_URL).setParameters(m).execute(new AsyncHandlerAdapter() {
            private StringBuilder builder = new StringBuilder();

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders<String> content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
                return STATE.CONTINUE;
            }

            @Override
            public STATE onBodyPartReceived(HttpResponseBodyPart<String> content) throws Exception {
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
    }

    @Test
    public void asyncStreamInterruptTest() throws Throwable {
        final CountDownLatch l = new CountDownLatch(1);
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");

        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < 5; i++) {
            m.put("param_" + i, "value_" + i);
        }


        final AtomicBoolean a = new AtomicBoolean(true);
        AsyncHttpClient c = new AsyncHttpClient();

        c.preparePost(TARGET_URL).setParameters(m).execute(new AsyncHandlerAdapter() {

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders<String> content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
                return STATE.ABORT;
            }

            @Override
            public STATE onBodyPartReceived(final HttpResponseBodyPart<String> content) throws Exception {
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
    }

    @Test
    public void asyncStreamFutureTest() throws Throwable {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < 5; i++) {
            m.put("param_" + i, "value_" + i);
        }
        AsyncHttpClient c = new AsyncHttpClient();

        Future<String> f = c.preparePost(TARGET_URL).setParameters(m).execute(new AsyncHandlerAdapter() {
            private StringBuilder builder = new StringBuilder();

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders<String> content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
                return STATE.CONTINUE;
            }

            @Override
            public STATE onBodyPartReceived(HttpResponseBodyPart<String> content) throws Exception {
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

        String r = f.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(r.trim(), RESPONSE);
    }

    @Test
    public void asyncStreamThrowableRefusedTest() throws Throwable {

        final CountDownLatch l = new CountDownLatch(1);
        AsyncHttpClient c = new AsyncHttpClient();

        c.prepareGet(TARGET_URL).execute(new AsyncHandlerAdapter() {

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders<String> content) throws Exception {
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
    }

    @Test
    public void asyncStreamReusePOSTTest() throws Throwable {
        final CountDownLatch l = new CountDownLatch(1);
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");

        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < 5; i++) {
            m.put("param_" + i, "value_" + i);
        }
        AsyncHttpClient c = new AsyncHttpClient();

        c.preparePost(TARGET_URL).setParameters(m).execute(new AsyncHandlerAdapter() {
            private StringBuilder builder = new StringBuilder();

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders<String> content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
                return STATE.CONTINUE;
            }

            @Override
            public STATE onBodyPartReceived(HttpResponseBodyPart<String> content) throws Exception {
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
        c.preparePost(TARGET_URL).setParameters(m).execute(new AsyncHandlerAdapter() {
            private StringBuilder builder = new StringBuilder();

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders<String> content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
                return STATE.CONTINUE;
            }

            @Override
            public STATE onBodyPartReceived(HttpResponseBodyPart<String> content) throws Exception {
                builder.append(new String(content.getBodyPartBytes()));
                return STATE.CONTINUE;
            }

            @Override
            public String onCompleted() throws Exception {
                String r = builder.toString().trim();
                Assert.assertEquals(r, RESPONSE);
                return r;
            }
        });

        if (!l.await(20, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

    @Test
    public void asyncStream301WithBody() throws Throwable {
        final CountDownLatch l = new CountDownLatch(1);
        AsyncHttpClient c = new AsyncHttpClient();
        c.prepareGet("http://google.com/").execute(new AsyncHandlerAdapter() {
            private StringBuilder builder = new StringBuilder();

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders<String> content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
                return STATE.CONTINUE;
            }

            @Override
            public STATE onBodyPartReceived(HttpResponseBodyPart<String> content) throws Exception {
                builder.append(new String(content.getBodyPartBytes()));
                return STATE.CONTINUE;
            }

            @Override
            public String onCompleted() throws Exception {
                try {
                    String r = builder.toString();
                    Assert.assertTrue(r.contains("301 Moved"));
                    l.countDown();
                    return r;
                } finally {
                    l.countDown();
                }
            }
        });

        if (!l.await(20, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

    @Test
    public void asyncStream301RedirectWithBody() throws Throwable {
        final CountDownLatch l = new CountDownLatch(1);
        AsyncHttpClient c = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
        c.prepareGet("http://google.com/").execute(new AsyncHandlerAdapter() {
            private StringBuilder builder = new StringBuilder();

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders<String> content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type"), "text/html; charset=ISO-8859-1");
                return STATE.CONTINUE;
            }

            @Override
            public STATE onBodyPartReceived(HttpResponseBodyPart<String> content) throws Exception {
                builder.append(new String(content.getBodyPartBytes()));
                return STATE.CONTINUE;
            }

            @Override
            public String onCompleted() throws Exception {
                try {
                    String r = builder.toString();
                    Assert.assertTrue(!r.contains("301 Moved"));
                    return r;
                } finally {
                    l.countDown();
                }
            }
        });

        if (!l.await(20, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

    @Test(timeOut = 3000, description = "Test behavior of 'read only status line' scenario.")
    public void asyncStreamJustStatusLine() throws Throwable {
        final int STATUS = 0;
        final int COMPLETED = 1;
        final int OTHER = 2;
        final boolean[] whatCalled = new boolean[]{false, false, false};
        final CountDownLatch latch = new CountDownLatch(1);
        AsyncHttpClient client = new AsyncHttpClient();
        Future<Integer> statusCode = client.prepareGet(TARGET_URL).execute(new AsyncHandler<Integer>() {
            private int status = -1;

            /* @Override */
            public void onThrowable(Throwable t) {
                whatCalled[OTHER] = true;
                latch.countDown();
            }

            /* @Override */
            public STATE onBodyPartReceived(HttpResponseBodyPart<Integer> bodyPart) throws Exception {
                whatCalled[OTHER] = true;
                latch.countDown();
                return STATE.ABORT;
            }

            /* @Override */
            public STATE onStatusReceived(HttpResponseStatus<Integer> responseStatus) throws Exception {
                whatCalled[STATUS] = true;
                System.out.println(responseStatus);
                status = responseStatus.getStatusCode();
                latch.countDown();
                return STATE.ABORT;
            }

            /* @Override */
            public STATE onHeadersReceived(HttpResponseHeaders<Integer> headers) throws Exception {
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
        Integer status = statusCode.get(1, TimeUnit.SECONDS);
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
    }


}
