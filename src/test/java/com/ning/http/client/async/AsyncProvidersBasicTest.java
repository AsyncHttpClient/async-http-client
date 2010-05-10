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

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpClientConfig.Builder;
import com.ning.http.client.Cookie;
import com.ning.http.client.Headers;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.Part;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.RequestType;
import com.ning.http.client.Response;
import com.ning.http.client.StringPart;
import com.ning.http.client.providers.NettyAsyncHttpProvider;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;

public class AsyncProvidersBasicTest extends AbstractBasicTest {

    @Test(groups = "async")
    public void asyncProviderContentLenghtGETTest() throws Throwable {
        NettyAsyncHttpProvider p = new NettyAsyncHttpProvider(new AsyncHttpClientConfig.Builder().build());
        final CountDownLatch l = new CountDownLatch(1);
        URL url = new URL(TARGET_URL);
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.connect();

        Request request = new RequestBuilder(RequestType.GET).setUrl(TARGET_URL).build();
        p.execute(request, new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    int contentLenght = -1;
                    if (response.getHeader("content-length") != null) {
                        contentLenght = Integer.valueOf(response.getHeader("content-length"));
                    }
                    int ct = connection.getContentLength();
                    assertEquals(contentLenght, ct);
                } finally {
                    l.countDown();
                }
                return response;
            }

            @Override
            public void onThrowable(Throwable t) {
                try {
                    Assert.fail("Unexpected exception", t);
                } finally {
                    l.countDown();
                }
            }


        }).get();

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

        p.close();
    }

    @Test(groups = "async")
    public void asyncContentTypeGETTest() throws Throwable {
        NettyAsyncHttpProvider p = new NettyAsyncHttpProvider(new AsyncHttpClientConfig.Builder().build());

        final CountDownLatch l = new CountDownLatch(1);
        Request request = new RequestBuilder(RequestType.GET).setUrl(TARGET_URL).build();
        p.execute(request, new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    assertEquals(response.getContentType(), "text/html; charset=utf-8");
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
        p.close();
    }

    @Test(groups = "async")
    public void asyncHeaderGETTest() throws Throwable {
        NettyAsyncHttpProvider n = new NettyAsyncHttpProvider(new AsyncHttpClientConfig.Builder().build());
        final CountDownLatch l = new CountDownLatch(1);
        Request request = new RequestBuilder(RequestType.GET).setUrl(TARGET_URL).build();
        n.execute(request, new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    assertEquals(response.getContentType(), "text/html; charset=utf-8");
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

    }

    @Test(groups = "async")
    public void asyncHeaderPOSTTest() throws Throwable {
        final CountDownLatch l = new CountDownLatch(1);
        NettyAsyncHttpProvider n = new NettyAsyncHttpProvider(new AsyncHttpClientConfig.Builder().build());

        Headers h = new Headers();
        h.add("Test1", "Test1");
        h.add("Test2", "Test2");
        h.add("Test3", "Test3");
        h.add("Test4", "Test4");
        h.add("Test5", "Test5");
        Request request = new RequestBuilder(RequestType.GET).setUrl(TARGET_URL).setHeaders(h).build();

        n.execute(request, new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    System.out.println(">>>>> " + response.getStatusText());
                    assertEquals(response.getStatusCode(), 200);
                    for (int i = 1; i < 5; i++) {
                        assertEquals(response.getHeader("X-Test" + i), "Test" + i);
                    }
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

    }

    @Test(groups = "async")
    public void asyncParamPOSTTest() throws Throwable {
        NettyAsyncHttpProvider n = new NettyAsyncHttpProvider(new AsyncHttpClientConfig.Builder().build());

        final CountDownLatch l = new CountDownLatch(1);
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");

        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < 5; i++) {
            m.put("param_" + i, "value_" + i);
        }
        Request request = new RequestBuilder(RequestType.POST).setUrl(TARGET_URL).setHeaders(h).setParameters(m).build();
        n.execute(request, new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    for (int i = 1; i < 5; i++) {
                        System.out.println(">>>>> " + response.getHeader("X-param_" + i));
                        assertEquals(response.getHeader("X-param_" + i), "value_" + i);
                    }

                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

    }

    @Test(groups = "async")
    public void asyncStatusHEADTest() throws Throwable {
        NettyAsyncHttpProvider n = new NettyAsyncHttpProvider(new AsyncHttpClientConfig.Builder().build());

        final CountDownLatch l = new CountDownLatch(1);
        Request request = new RequestBuilder(RequestType.HEAD).setUrl(TARGET_URL).build();
        n.execute(request, new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

    }

    @Test(groups = "async", enabled = false)
    public void asyncStatusHEADContentLenghtTest() throws Throwable {
        NettyAsyncHttpProvider n = new NettyAsyncHttpProvider(new AsyncHttpClientConfig.Builder()
                .setRequestTimeoutInMs(120 * 1000).build());

        final CountDownLatch l = new CountDownLatch(1);
        Request request = new RequestBuilder(RequestType.HEAD)
                .setUrl(TARGET_URL)
                .build();

        n.execute(request, new AsyncCompletionHandlerAdapter() {
            @Override
            public Response onCompleted(Response response) throws Exception {
                Assert.fail();
                return response;
            }

            @Override
            public void onThrowable(Throwable t) {
                try {
                    assertEquals(t.getClass(), IOException.class);
                    assertEquals(t.getMessage(), "No response received. Connection timed out");
                } finally {
                    l.countDown();
                }

            }
        }).get();

        if (!l.await(10 * 5 * 1000, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

    }

    @Test(groups = "async")
    public void asyncNullSchemeTest() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();

        try {
            c.prepareGet("www.sun.com").execute();
            Assert.fail();
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(true);
        }

    }

    @Test(groups = "async")
    public void asyncDoGetTransferEncodingTest() throws Throwable {
        NettyAsyncHttpProvider n = new NettyAsyncHttpProvider(new AsyncHttpClientConfig.Builder().build());

        AsyncHttpClient c = new AsyncHttpClient(n);
        final CountDownLatch l = new CountDownLatch(1);

        c.prepareGet(TARGET_URL).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    assertEquals(response.getHeader("Transfer-Encoding"), "chunked");
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

    }

    @Test(groups = "async")
    public void asyncDoGetHeadersTest() throws Throwable {
        NettyAsyncHttpProvider n = new NettyAsyncHttpProvider(new AsyncHttpClientConfig.Builder().build());

        AsyncHttpClient c = new AsyncHttpClient(n);
        final CountDownLatch l = new CountDownLatch(1);
        Headers h = new Headers();
        h.add("Test1", "Test1");
        h.add("Test2", "Test2");
        h.add("Test3", "Test3");
        h.add("Test4", "Test4");
        h.add("Test5", "Test5");
        c.prepareGet(TARGET_URL).setHeaders(h).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    for (int i = 1; i < 5; i++) {
                        assertEquals(response.getHeader("X-Test" + i), "Test" + i);
                    }
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();
        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

    }

    @Test(groups = "async")
    public void asyncDoGetCookieTest() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();
        final CountDownLatch l = new CountDownLatch(1);
        Headers h = new Headers();
        h.add("Test1", "Test1");
        h.add("Test2", "Test2");
        h.add("Test3", "Test3");
        h.add("Test4", "Test4");
        h.add("Test5", "Test5");

        final Cookie coo = new Cookie("/", "foo", "value", "/", -1, false);
        c.prepareGet(TARGET_URL).setHeaders(h).addCookie(coo).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    List<Cookie> cookies = response.getCookies();
                    assertEquals(cookies.size(), 1);
                    assertEquals(cookies.get(0).toString(), coo.toString());
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

    @Test(groups = "async")
    public void asyncDoPostDefaultContentType() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();
        final CountDownLatch l = new CountDownLatch(1);
        c.preparePost(TARGET_URL).addParameter("foo", "bar").execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    Headers h = response.getHeaders();
                    assertEquals(h.getHeaderValue("X-Content-Type"), "application/x-www-form-urlencoded");
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

    @Test(groups = "async")
    public void asyncDoPostBytesTest() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();
        final CountDownLatch l = new CountDownLatch(1);
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("param_");
            sb.append(i);
            sb.append("=value_");
            sb.append(i);
            sb.append("&");
        }
        sb.deleteCharAt(sb.length() - 1);

        c.preparePost(TARGET_URL).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    for (int i = 1; i < 5; i++) {
                        System.out.println(">>>>> " + response.getHeader("X-param_" + i));
                        assertEquals(response.getHeader("X-param_" + i), "value_" + i);

                    }
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();
        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

    @Test(groups = "async")
    public void asyncDoPostInputStreamTest() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();
        final CountDownLatch l = new CountDownLatch(1);
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("param_");
            sb.append(i);
            sb.append("=value_");
            sb.append(i);
            sb.append("&");
        }
        sb.deleteCharAt(sb.length() - 1);
        ByteArrayInputStream is = new ByteArrayInputStream(sb.toString().getBytes());

        c.preparePost(TARGET_URL).setHeaders(h).setBody(is).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    for (int i = 1; i < 5; i++) {
                        System.out.println(">>>>> " + response.getHeader("X-param_" + i));
                        assertEquals(response.getHeader("X-param_" + i), "value_" + i);

                    }
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();
        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

    @Test(groups = "async")
    public void asyncDoPutInputStreamTest() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();
        final CountDownLatch l = new CountDownLatch(1);
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("param_");
            sb.append(i);
            sb.append("=value_");
            sb.append(i);
            sb.append("&");
        }
        sb.deleteCharAt(sb.length() - 1);
        ByteArrayInputStream is = new ByteArrayInputStream(sb.toString().getBytes());

        c.preparePut(TARGET_URL).setHeaders(h).setBody(is).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    for (int i = 1; i < 5; i++) {
                        System.out.println(">>>>> " + response.getHeader("X-param_" + i));
                        assertEquals(response.getHeader("X-param_" + i), "value_" + i);

                    }
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();
        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

    @Test(groups = "async")
    public void asyncDoPostEntityWriterTest() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();
        final CountDownLatch l = new CountDownLatch(1);
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");

        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("param_");
            sb.append(i);
            sb.append("=value_");
            sb.append(i);
            sb.append("&");
        }
        sb.deleteCharAt(sb.length() - 1);
        byte[] bytes = sb.toString().getBytes();
        h.add("Content-Length", String.valueOf(bytes.length));

        c.preparePost(TARGET_URL).setHeaders(h).setBody(new Request.EntityWriter() {

            /* @Override */
            public void writeEntity(OutputStream out) throws IOException {
                out.write(sb.toString().getBytes("UTF-8"));
            }
        }).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    for (int i = 1; i < 5; i++) {
                        System.out.println(">>>>> " + response.getHeader("X-param_" + i));
                        assertEquals(response.getHeader("X-param_" + i), "value_" + i);
                    }
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();
        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

    @Test(groups = "async")
    public void asyncDoPostMultiPartTest() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();
        final CountDownLatch l = new CountDownLatch(1);

        Part p = new StringPart("foo", "bar");

        c.preparePost(TARGET_URL).addBodyPart(p).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    String xContentType = response.getHeader("X-Content-Type");
                    String boundary = xContentType.substring(
                            (xContentType.indexOf("boundary") + "boundary".length() + 1));

                    String s = response.getResponseBodyExcerpt(boundary.length() + "--".length()).substring("--".length());
                    assertEquals(boundary, s);
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();
        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
    }

    @Test(groups = "async")
    public void asyncDoPostBasicGZIPTest() throws Throwable {

        AsyncHttpClientConfig cf = new AsyncHttpClientConfig.Builder().setCompressionEnabled(true).build();
        AsyncHttpClient c = new AsyncHttpClient(cf);
        final CountDownLatch l = new CountDownLatch(1);
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("param_");
            sb.append(i);
            sb.append("=value_");
            sb.append(i);
            sb.append("&");
        }
        sb.deleteCharAt(sb.length() - 1);

        c.preparePost(TARGET_URL).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    assertEquals(response.getHeader("X-Accept-Encoding"), "gzip");
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();
        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

    }

    @Test(groups = "async")
    public void asyncDoPostProxyTest() throws Throwable {

        AsyncHttpClientConfig cf = new AsyncHttpClientConfig.Builder().setProxyServer(new ProxyServer("127.0.0.1", 38080)).build();
        AsyncHttpClient c = new AsyncHttpClient(cf);

        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("param_");
            sb.append(i);
            sb.append("=value_");
            sb.append(i);
            sb.append("&");
        }
        sb.deleteCharAt(sb.length() - 1);

        Response response = c.preparePost(TARGET_URL).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandler<Response>() {
            @Override
            public Response onCompleted(Response response) throws Exception {
                return response;
            }

            /* @Override */
            public void onThrowable(Throwable t) {
            }
        }).get();


        assertEquals(response.getStatusCode(), 200);
        assertEquals(response.getHeader("X-Proxy-Connection"), "keep-alive");
    }


    @Test(groups = "async")
    public void asyncRequestVirtualServerPOSTTest() throws Throwable {
        NettyAsyncHttpProvider n = new NettyAsyncHttpProvider(new AsyncHttpClientConfig.Builder().build());

        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");

        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < 5; i++) {
            m.put("param_" + i, "value_" + i);
        }
        Request request = new RequestBuilder(RequestType.POST)
                .setUrl(TARGET_URL)
                .setHeaders(h)
                .setParameters(m)
                .setVirtualHost("localhost")
                .build();

        Response response = n.execute(request, new AsyncCompletionHandlerAdapter()).get();

        assertEquals(response.getStatusCode(), 200);
        assertEquals(response.getHeader("X-Host"), "localhost:19999");

    }

    @Test(groups = "async")
    public void asyncDoPutTest() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("param_");
            sb.append(i);
            sb.append("=value_");
            sb.append(i);
            sb.append("&");
        }
        sb.deleteCharAt(sb.length() - 1);

        Response response = c.preparePut(TARGET_URL).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter()).get();

        assertEquals(response.getStatusCode(), 200);

    }

    @Test(groups = "async")
    public void asyncDoPostLatchBytesTest() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();
        final CountDownLatch l = new CountDownLatch(1);
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("param_");
            sb.append(i);
            sb.append("=value_");
            sb.append(i);
            sb.append("&");
        }
        sb.deleteCharAt(sb.length() - 1);

        c.preparePost(TARGET_URL).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    for (int i = 1; i < 5; i++) {
                        System.out.println(">>>>> " + response.getHeader("X-param_" + i));
                        assertEquals(response.getHeader("X-param_" + i), "value_" + i);

                    }
                    return response;
                } finally {
                    l.countDown();
                }
            }
        });

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

    }

    @Test(groups = "async")
    public void asyncDoPostDelayCancelTest() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        h.add("LockThread", "true");
        StringBuilder sb = new StringBuilder();
        sb.append("LockThread=true");

        Future<Response> future = c.preparePost(TARGET_URL).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter());
        future.cancel(true);
        Response response = future.get(TIMEOUT, TimeUnit.SECONDS);
        Assert.assertNull(response);
        c.close();
    }

    @Test(groups = "async")
    public void asyncDoPostDelayBytesTest() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        h.add("LockThread", "true");
        StringBuilder sb = new StringBuilder();
        sb.append("LockThread=true");

        try {
            Future<Response> future = c.preparePost(TARGET_URL).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {
                @Override
                public void onThrowable(Throwable t) {
                    t.printStackTrace();
                }
            });

            future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            Assert.assertTrue(true);
        } catch (IllegalStateException ex) {
            Assert.assertTrue(false);
        }
        c.close();
    }

    @Test(groups = "async")
    public void asyncDoPostNullBytesTest() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("param_");
            sb.append(i);
            sb.append("=value_");
            sb.append(i);
            sb.append("&");
        }
        sb.deleteCharAt(sb.length() - 1);

        Future<Response> future = c.preparePost(TARGET_URL).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter());

        Response response = future.get();
        Assert.assertNotNull(response);
        assertEquals(response.getStatusCode(), 200);

    }

    @Test(groups = "async")
    public void asyncDoPostListenerBytesTest() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("param_");
            sb.append(i);
            sb.append("=value_");
            sb.append(i);
            sb.append("&");
        }
        sb.deleteCharAt(sb.length() - 1);

        final CountDownLatch l = new CountDownLatch(1);

        c.preparePost(TARGET_URL).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                } finally {
                    l.countDown();
                }
                return response;
            }
        });

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Latch time out");
        }
    }

    @Test(groups = "async")
    public void asyncConnectInvalidPortFuture() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("param_");
            sb.append(i);
            sb.append("=value_");
            sb.append(i);
            sb.append("&");
        }
        sb.deleteCharAt(sb.length() - 1);

        ConnectException expected = null;
        try {
            c.preparePost("http://127.0.0.1:9999/").setHeaders(h).setBody(sb.toString())
                    .execute(new AsyncCompletionHandlerAdapter() {
                        /* @Override */
                        public void onThrowable(Throwable t) {
                        }
                    }).get();
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof ConnectException) {
                expected = (ConnectException) ex.getCause();
            }
        }

        if (expected != null) {
            assertEquals(expected.getClass(), ConnectException.class);
        } else {
            Assert.fail("Must have thrown an ExecutionException");
        }
    }

    @Test(groups = "async")
    public void asyncConnectInvalidPort() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();
        Headers h = new Headers();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("param_");
            sb.append(i);
            sb.append("=value_");
            sb.append(i);
            sb.append("&");
        }
        sb.deleteCharAt(sb.length() - 1);

        try {
            c.preparePost("http://127.0.0.1:9999/").setHeaders(h).setBody(sb.toString())
                    .execute(new AsyncCompletionHandlerAdapter() {
                        /* @Override */
                        public void onThrowable(Throwable t) {
                        }
                    }).get();
            Assert.assertTrue(false);
        } catch (ExecutionException ex) {
            assertEquals(ex.getCause().getClass(), ConnectException.class);
        }
    }

    @Test(groups = "async")
    public void asyncConnectInvalidHandlerPort() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();
        final CountDownLatch l = new CountDownLatch(1);

        c.prepareGet("http://127.0.0.1:9999/").execute(new AsyncCompletionHandlerAdapter() {
            /* @Override */
            public void onThrowable(Throwable t) {
                try {
                    assertEquals(t.getClass(), ConnectException.class);
                } finally {
                    l.countDown();
                }
            }
        });

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
    }

    @Test(groups = "async")
    public void asyncConnectInvalidHandlerHost() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();
        final CountDownLatch l = new CountDownLatch(1);

        c.prepareGet("http://null.apache.org:9999/").execute(new AsyncCompletionHandlerAdapter() {
            /* @Override */
            public void onThrowable(Throwable t) {
                try {
                    assertEquals(t.getClass(), ConnectException.class);
                } finally {
                    l.countDown();
                }
            }
        });

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
    }


    @Test(groups = "async")
    public void asyncConnectInvalidFuturePort() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();

        try {
            c.prepareGet("http://127.0.0.1:9999/").execute(new AsyncCompletionHandlerAdapter() {
                /* @Override */
                public void onThrowable(Throwable t) {
                }
            }).get();
            Assert.fail("No ConnectionException was thrown");
        } catch (ExecutionException ex) {
            assertEquals(ex.getCause().getClass(), ConnectException.class);
        }

    }

    @Test(groups = "async")
    public void asyncContentLenghtGETTest() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();
        Response response = c.prepareGet(TARGET_URL).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public void onThrowable(Throwable t) {
                Assert.fail("Unexpected exception", t);
            }
        }).get();

        Assert.assertNotNull(response);
        assertEquals(response.getStatusCode(), 200);
    }

    @Test(groups = "async")
    public void asyncResponseBodyTooLarge() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();
        Response response = c.prepareGet(TARGET_URL).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public void onThrowable(Throwable t) {
                Assert.fail("Unexpected exception", t);
            }
        }).get();

        Assert.assertNotNull(response.getResponseBodyExcerpt(Integer.MAX_VALUE));
    }

    @Test(groups = "async")
    public void asyncResponseBody() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();
        Response response = c.prepareGet(TARGET_URL).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public void onThrowable(Throwable t) {
                Assert.fail("Unexpected exception", t);
            }
        }).get();

        Assert.assertNotNull(response.getResponseBody());
    }

    @Test(groups = "asyncAPI")
    public void asyncAPIContentLenghtGETTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient();

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(1);

        client.prepareGet(TARGET_URL).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                } finally {
                    l.countDown();
                }
                return response;
            }

            @Override
            public void onThrowable(Throwable t) {
            }
        });


        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
    }

    @Test(groups = "asyncAPI")
    public void asyncAPIHandlerExceptionTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient();

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(1);

        client.prepareGet(TARGET_URL).execute(new AsyncCompletionHandlerAdapter() {
            @Override
            public Response onCompleted(Response response) throws Exception {
                throw new IllegalStateException("FOO");
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


        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
    }

    @Test(groups = "async")
    public void asyncDoGetDelayHandlerTest() throws Throwable {
        Headers h = new Headers();
        h.add("LockThread", "true");
        AsyncHttpClient client = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(5 * 1000).build());

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(1);

        client.prepareGet(TARGET_URL).setHeaders(h).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    Assert.fail("Must not receive a response");
                } finally {
                    l.countDown();
                }
                return response;
            }

            @Override
            public void onThrowable(Throwable t) {
                try {
                    if (t instanceof TimeoutException) {
                        Assert.assertTrue(true);
                    } else {
                        Assert.fail("Unexpected exception", t);
                    }
                } finally {
                    l.countDown();
                }
            }
        });

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
    }

    @Test(groups = "async")
    public void asyncDoGetQueryStringTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient();

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(1);

        AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    Assert.assertTrue(response.getHeader("X-pathInfo") != null);
                    Assert.assertTrue(response.getHeader("X-queryString") != null);
                } finally {
                    l.countDown();
                }
                return response;
            }
        };

        Request req = new RequestBuilder(RequestType.GET)
                .setUrl(TARGET_URL + "?foo=bar").build();

        client.executeRequest(req, handler).get();

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
    }

    @Test(groups = "async")
    public void asyncDoGetKeepAliveHandlerTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient();

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(2);

        AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

            String remoteAddr = null;

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    if (remoteAddr == null) {
                        remoteAddr = response.getHeader("X-KEEP-ALIVE");
                    } else {
                        assertEquals(response.getHeader("X-KEEP-ALIVE"), remoteAddr);
                    }
                } finally {
                    l.countDown();
                }
                return response;
            }
        };

        client.prepareGet(TARGET_URL).execute(handler).get();
        client.prepareGet(TARGET_URL).execute(handler);

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
    }


    @Test(groups = "async")
    public void asyncDoGetMaxConnectionsTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient(new Builder().setMaximumConnectionsTotal(2).build());

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(2);

        AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                l.countDown();
                return response;
            }

            @Override
            public void onThrowable(Throwable t) {
                try {
                    Assert.fail("Unexpected exception", t);
                } finally {
                    l.countDown();
                }
            }
        };

        client.prepareGet("http://www.oracle.com/index.html").execute(handler).get();
        client.prepareGet("http://www.apache.org/").execute(handler).get();

        try {
            client.prepareGet("http://www.ning.com/").execute(handler).get();
            Assert.fail();
        } catch (IOException ex) {
            String s = ex.getMessage();
            assertEquals(s, "Too many connections");
        }

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
        client.close();
    }

    @Test(groups = "async")
    public void asyncDoGetMaxRedirectTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient(new Builder().setMaximumNumberOfRedirects(0).setFollowRedirects(true).build());

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(1);

        AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                Assert.fail("Should not be here");
                return response;
            }

            @Override
            public void onThrowable(Throwable t) {
                t.printStackTrace();
                try {
                    assertEquals(t.getClass(), MaxRedirectException.class);
                } finally {
                    l.countDown();
                }
            }
        };

        client.prepareGet("http://google.com/").execute(handler);

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
        client.close();
    }

    @Test(groups = "async")
    public void asyncDoGetNestedTest() throws Throwable {
        final AsyncHttpClient client = new AsyncHttpClient(new Builder().build());

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(2);

        final AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

            private final static int MAX_NESTED = 2;

            private AtomicInteger nestedCount = new AtomicInteger(0);

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    if (nestedCount.getAndIncrement() < MAX_NESTED) {
                        System.out.println("Executing a nested request: " + nestedCount);
                        client.prepareGet("http://google.com/").execute(this);
                    }
                } finally {
                    l.countDown();
                }
                return response;
            }

            @Override
            public void onThrowable(Throwable t) {
                Assert.assertFalse(true);
            }
        };

        client.prepareGet("http://www.google.com/").execute(handler);

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
        client.close();
    }

    @Test(groups = "async")
    public void asyncDoGetStreamAndBodyTest() throws Throwable {
        final AsyncHttpClient client = new AsyncHttpClient(new Builder().build());
        Response r = client.prepareGet("http://www.google.com/").execute().get();

        r.getResponseBody();
        r.getResponseBodyAsStream();

        client.close();
    }

    @Test(groups = "async")
    public void asyncUrlWithoutPath() throws Throwable {
        final AsyncHttpClient client = new AsyncHttpClient(new Builder().build());
        Response r = client.prepareGet("http://www.google.com").execute().get();

        r.getResponseBody();
        r.getResponseBodyAsStream();

        client.close();
    }
}
