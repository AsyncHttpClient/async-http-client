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

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Headers;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AsyncStreamHandlerTest extends AbstractBasicTest {

    private final static String RESPONSE_JDK5 = "param_4=value_4&param_2=value_2&param_0=value_0&param_3=value_3&param_1=value_1";
    private final static String RESPONSE_JDK6 = "param_4=value_4&param_0=value_0&param_1=value_1&param_2=value_2&param_3=value_3";

    private final static String RESPONSE = System.getProperty("java.version").startsWith("1.5") ? RESPONSE_JDK5: RESPONSE_JDK6;

    private final static String UTF8 = "text/html; charset=utf-8";
    @Test
    public void asyncStreamGETTest() throws Throwable {
        final CountDownLatch l = new CountDownLatch(1);
        AsyncHttpClient c = new AsyncHttpClient();

        c.prepareGet(TARGET_URL).execute(new AsyncHandlerAdapter() {

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
                l.countDown();
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
            public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
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
                Assert.assertEquals(r, RESPONSE);
                l.countDown();
                return r;
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
            public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
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
    }

    @Test
    public void asyncStreamFutureTest() throws Throwable {
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");

        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < 5; i++) {
            m.put("param_" + i, "value_" + i);
        }
        AsyncHttpClient c = new AsyncHttpClient();

        Future<String> f = c.preparePost(TARGET_URL).setParameters(m).execute(new AsyncHandlerAdapter() {
            private StringBuilder builder = new StringBuilder();

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
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
                Assert.assertEquals(r, RESPONSE);
                return r;
            }


            @Override
            public void onThrowable(Throwable t) {
                try {
                    Assert.fail("", t);
                } finally {

                }
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
            public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                throw new RuntimeException("FOO");
            }

            @Override

            public void onThrowable(Throwable t) {
                t.printStackTrace();
                if (t.getMessage() != null) {
                    Assert.assertEquals(t.getMessage(), "FOO");
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
            public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
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
                Assert.assertEquals(r, RESPONSE);
                l.countDown();
                return r;
            }
        });

        if (!l.await(20, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

        // Let do the same again
        c.preparePost(TARGET_URL).setParameters(m).execute(new AsyncHandlerAdapter() {
            private StringBuilder builder = new StringBuilder();

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
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
            public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type").toLowerCase(), UTF8);
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
    }

    @Test
    public void asyncStream301RedirectWithBody() throws Throwable {
        final CountDownLatch l = new CountDownLatch(1);
        AsyncHttpClient c = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
        c.prepareGet("http://google.com/").execute(new AsyncHandlerAdapter() {
            private StringBuilder builder = new StringBuilder();

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders content) throws Exception {
                Headers h = content.getHeaders();
                Assert.assertNotNull(h);
                Assert.assertEquals(h.getHeaderValue("content-type"), "text/html; charset=ISO-8859-1");
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
    }

}
