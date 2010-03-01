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
import com.ning.http.client.AsyncStreamingHandler;
import com.ning.http.client.Headers;
import com.ning.http.client.HttpContent;
import com.ning.http.client.HttpResponseBody;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.Response;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AsyncStreamHandlerTest extends AbstractBasicTest {

    private final static String RESPONSE = "param_4=value_4&param_0=value_0&param_1=value_1&param_2=value_2&param_3=value_3";

    @Test
    public void asyncStreamGETTest() throws Throwable {
        final CountDownLatch l = new CountDownLatch(1);
        AsyncHttpClient c = new AsyncHttpClient();

        c.prepareGet(TARGET_URL).execute(new AsyncStreamingHandler() {

            @Override
            public Response onContentReceived(HttpContent content) {
                if (content instanceof HttpResponseHeaders) {
                    Headers h = ((HttpResponseHeaders) content).getResponse().getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getHeaderValue("content-type"), "text/html; charset=utf-8");
                    l.countDown();
                    throw new ResponseComplete();
                }
                return content.getResponse();
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

        c.preparePost(TARGET_URL).setParameters(m).execute(new AsyncStreamingHandler() {

            @Override
            public Response onContentReceived(HttpContent content) {
                if (content instanceof HttpResponseHeaders) {
                    Headers h = ((HttpResponseHeaders) content).getResponse().getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getHeaderValue("content-type"), "text/html; charset=utf-8");
                } else if (content instanceof HttpResponseBody) {
                    HttpResponseBody b = (HttpResponseBody) content;
                    if (b.isComplete()) {
                        try {
                            InputStream is = b.getResponse().getResponseBodyAsStream();
                            byte[] response = new byte[is.available()];
                            is.read(response, 0, is.available());

                            Assert.assertEquals(new String(response), RESPONSE);
                        } catch (IOException ex) {
                            Assert.fail("", ex);
                        } finally {
                            l.countDown();
                        }
                        throw new ResponseComplete();
                    }
                }
                return content.getResponse();
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

        if (!l.await(20, TimeUnit.SECONDS)) {
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

        c.preparePost(TARGET_URL).setParameters(m).execute(new AsyncStreamingHandler() {

            @Override
            public Response onContentReceived(HttpContent content) {
                if (content instanceof HttpResponseHeaders) {
                    Headers h = ((HttpResponseHeaders) content).getResponse().getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getHeaderValue("content-type"), "text/html; charset=utf-8");
                    throw new ResponseComplete();
                } else if (content instanceof HttpResponseBody) {
                    a.set(false);
                    Assert.fail("Interrupted not working");
                }
                return content.getResponse();
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

        Future<Response> f = c.preparePost(TARGET_URL).setParameters(m).execute(new AsyncStreamingHandler() {

            @Override
            public Response onContentReceived(HttpContent content) {
                if (content instanceof HttpResponseHeaders) {
                    Headers h = ((HttpResponseHeaders) content).getResponse().getHeaders();
                    Assert.assertNotNull(h);
                }  else {
                    HttpResponseBody b = (HttpResponseBody) content;
                    if (b.isComplete()) {
                        throw new ResponseComplete();
                    }
                }
                return content.getResponse();
            }

            @Override
            public void onThrowable(Throwable t) {
                try {
                    Assert.fail("", t);
                } finally {

                }
            }
        });

        Response r = f.get(5, TimeUnit.SECONDS);
        InputStream is = r.getResponseBodyAsStream();
        byte[] response = new byte[is.available()];
        is.read(response, 0, is.available());

        Assert.assertEquals(new String(response).trim(), RESPONSE);
    }

    @Test
    public void asyncStreamThrowableRefusedTest() throws Throwable {

        final CountDownLatch l = new CountDownLatch(1);
        AsyncHttpClient c = new AsyncHttpClient();

        c.prepareGet(TARGET_URL).execute(new AsyncStreamingHandler() {

            @Override
            public Response onContentReceived(HttpContent content) {
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

        c.preparePost(TARGET_URL).setParameters(m).execute(new AsyncStreamingHandler() {

            @Override
            public Response onContentReceived(HttpContent content) {
                if (content instanceof HttpResponseHeaders) {
                    Headers h = ((HttpResponseHeaders) content).getResponse().getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getHeaderValue("content-type"), "text/html; charset=utf-8");

                } else if (content instanceof HttpResponseBody) {
                    HttpResponseBody b = (HttpResponseBody) content;
                    if (b.isComplete()) {
                        try {
                            InputStream is = b.getResponse().getResponseBodyAsStream();
                            byte[] response = new byte[is.available()];
                            is.read(response, 0, is.available());

                            Assert.assertEquals(new String(response).trim(), RESPONSE);
                        } catch (IOException ex) {
                            Assert.fail("", ex);
                        } finally {
                            l.countDown();
                        }
                        throw new ResponseComplete();
                    }
                }
                return content.getResponse();
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

        if (!l.await(20, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

        // Let do the same again
        c.preparePost(TARGET_URL).setParameters(m).execute(new AsyncStreamingHandler() {

            @Override
            public Response onContentReceived(HttpContent content) throws ResponseComplete
        {
                if (content instanceof HttpResponseHeaders) {
                    Headers h = ((HttpResponseHeaders) content).getResponse().getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getHeaderValue("content-type"), "text/html; charset=utf-8");

                } else if (content instanceof HttpResponseBody) {
                    HttpResponseBody b = (HttpResponseBody) content;
                    if (b.isComplete()) {
                        try {
                            InputStream is = b.getResponse().getResponseBodyAsStream();
                            byte[] response = new byte[is.available()];
                            is.read(response, 0, is.available());

                            Assert.assertEquals(new String(response).trim(), RESPONSE);
                        } catch (IOException ex) {
                            Assert.fail("", ex);
                        } finally {
                            l.countDown();
                        }
                        throw new ResponseComplete();
                    }
                }
                return content.getResponse();
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

        if (!l.await(20, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

    @Test
    public void asyncStream301WithBody() throws Throwable {
        final CountDownLatch l = new CountDownLatch(1);
        AsyncHttpClient c = new AsyncHttpClient();
        c.prepareGet("http://google.com/").execute(new AsyncStreamingHandler() {
            @Override
            public Response onContentReceived(HttpContent content) {
                if (content instanceof HttpResponseHeaders) {
                    Headers h = content.getResponse().getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getHeaderValue("content-type"), "text/html; charset=UTF-8");
                    Assert.assertEquals(content.getResponse().getStatusCode(), 301);
                } else if (content instanceof HttpResponseBody) {
                    HttpResponseBody b = (HttpResponseBody) content;
                    if (b.isComplete()) {
                        try {
                            InputStream is = b.getResponse().getResponseBodyAsStream();
                            byte[] response = new byte[is.available()];
                            is.read(response, 0, is.available());
                            Assert.assertTrue(new String(response).contains("301 Moved"));
                        } catch (IOException ex) {
                            Assert.fail("", ex);
                        } finally {
                            l.countDown();
                        }
                        throw new ResponseComplete();
                    }
                }
                return content.getResponse();
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

        if (!l.await(20, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

    @Test
    public void asyncStream301RedirectWithBody() throws Throwable {
        final CountDownLatch l = new CountDownLatch(1);
        AsyncHttpClient c = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
        c.prepareGet("http://google.com/").execute(new AsyncStreamingHandler() {
            @Override
            public Response onContentReceived(HttpContent content) {
                if (content instanceof HttpResponseHeaders) {
                    Headers h = content.getResponse().getHeaders();
                    Assert.assertNotNull(h);
                    Assert.assertEquals(h.getHeaderValue("content-type"), "text/html; charset=ISO-8859-1");
                    Assert.assertEquals(content.getResponse().getStatusCode(), 200);
                } else if (content instanceof HttpResponseBody) {
                    HttpResponseBody b = (HttpResponseBody) content;
                    if (b.isComplete()) {
                        try {
                            InputStream is = b.getResponse().getResponseBodyAsStream();
                            byte[] response = new byte[is.available()];
                            is.read(response, 0, is.available());
                            String s = new String(response);
                            Assert.assertTrue(s.contains("Search settings"));
                        } catch (IOException ex) {
                            Assert.fail("", ex);
                        } finally {
                            l.countDown();
                        }
                        throw new ResponseComplete();
                    }
                }
                return content.getResponse();
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

        if (!l.await(20, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

//    @Test
//    public void asyncChainedStreamFutureTest() throws Throwable {
//        Headers h = new Headers();
//        h.add("Content-Type", "application/x-www-form-urlencoded");
//
//        Map<String, String> m = new HashMap<String, String>();
//        for (int i = 0; i < 5; i++) {
//            m.put("param_" + i, "value_" + i);
//        }
//        AsyncHttpClient c = new AsyncHttpClient();
//
//        Future<Response> f = c.doPost(TARGET_URL,m,new AsyncStreamingHandler() {
//
//            @Override
//            public Response onContentReceived(HttpContent content) throws ResponseComplete{
//                return content.getResponse();
//            }
//
//            @Override
//            public void onThrowable(Throwable t) {
//                try {
//                    Assert.fail("", t);
//                } finally {
//
//                }
//            }
//        });
//
//        Response r = c.write(ash).get(5, TimeUnit.SECONDS);
//
//        Response r2 = c.write(ash).get(5, TimeUnit.SECONDS);
//
//        InputStream is = r.getResponseBodyAsStream();
//        byte[] response = new byte[is.available()];
//        is.read(response, 0, is.available());
//
//        Assert.assertEquals(new String(response).trim(), RESPONSE);
//
//        is = r2.getResponseBodyAsStream();
//        response = new byte[is.available()];
//        is.read(response, 0, is.available());
//
//        Assert.assertEquals(new String(response).trim(), RESPONSE);
//    }
}
