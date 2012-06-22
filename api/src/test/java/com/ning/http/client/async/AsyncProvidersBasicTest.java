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
import com.ning.http.client.AsyncHttpClientConfigBean;
import com.ning.http.client.AsyncHttpProviderConfig;
import com.ning.http.client.Cookie;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.Part;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.StringPart;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.UnresolvedAddressException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;


public abstract class AsyncProvidersBasicTest extends AbstractBasicTest {
    private static final String UTF_8 = "text/html;charset=UTF-8";

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncProviderEncodingTest() throws Throwable {
        AsyncHttpClient p = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        Request request = new RequestBuilder("GET").setUrl("http://foo.com/foo.html?q=+%20x").build();
        String requestUrl = request.getUrl();
        Assert.assertEquals(requestUrl, "http://foo.com/foo.html?q=%20%20x");
        Future<String> responseFuture = p.executeRequest(request, new AsyncCompletionHandler<String>() {
            @Override
            public String onCompleted(Response response) throws Exception {
                return response.getUri().toString();
            }

            /* @Override */
            public void onThrowable(Throwable t) {
                t.printStackTrace();
                Assert.fail("Unexpected exception: " + t.getMessage(), t);
            }

        });
        String url = responseFuture.get();
        Assert.assertEquals(url, "http://foo.com/foo.html?q=%20%20x");
        p.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncProviderEncodingTest2() throws Throwable {
        AsyncHttpClient p = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        Request request = new RequestBuilder("GET").setUrl("http://foo.com/foo.html")
                .addQueryParameter("q", "a b")
                .build();

        Future<String> responseFuture = p.executeRequest(request, new AsyncCompletionHandler<String>() {
            @Override
            public String onCompleted(Response response) throws Exception {
                return response.getUri().toString();
            }

            /* @Override */
            public void onThrowable(Throwable t) {
                t.printStackTrace();
                Assert.fail("Unexpected exception: " + t.getMessage(), t);
            }

        });
        String url = responseFuture.get();
        Assert.assertEquals(url, "http://foo.com/foo.html?q=a%20b");
        p.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void emptyRequestURI() throws Throwable {
        AsyncHttpClient p = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        Request request = new RequestBuilder("GET").setUrl("http://foo.com")
                .build();

        Future<String> responseFuture = p.executeRequest(request, new AsyncCompletionHandler<String>() {
            @Override
            public String onCompleted(Response response) throws Exception {
                return response.getUri().toString();
            }

            /* @Override */
            public void onThrowable(Throwable t) {
                t.printStackTrace();
                Assert.fail("Unexpected exception: " + t.getMessage(), t);
            }

        });
        String url = responseFuture.get();
        Assert.assertEquals(url, "http://foo.com/");
        p.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncProviderContentLenghtGETTest() throws Throwable {
        AsyncHttpClient p = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        final CountDownLatch l = new CountDownLatch(1);
        URL url = new URL(getTargetUrl());
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.connect();

        Request request = new RequestBuilder("GET").setUrl(getTargetUrl()).build();
        p.executeRequest(request, new AsyncCompletionHandlerAdapter() {

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

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncContentTypeGETTest() throws Throwable {
        AsyncHttpClient p = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());

        final CountDownLatch l = new CountDownLatch(1);
        Request request = new RequestBuilder("GET").setUrl(getTargetUrl()).build();
        p.executeRequest(request, new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    assertEquals(response.getContentType(), UTF_8);
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

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncHeaderGETTest() throws Throwable {
        AsyncHttpClient n = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        final CountDownLatch l = new CountDownLatch(1);
        Request request = new RequestBuilder("GET").setUrl(getTargetUrl()).build();
        n.executeRequest(request, new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    assertEquals(response.getContentType(), UTF_8);
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
        n.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncHeaderPOSTTest() throws Throwable {
        final CountDownLatch l = new CountDownLatch(1);
        AsyncHttpClient n = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());

        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        h.add("Test1", "Test1");
        h.add("Test2", "Test2");
        h.add("Test3", "Test3");
        h.add("Test4", "Test4");
        h.add("Test5", "Test5");
        Request request = new RequestBuilder("GET").setUrl(getTargetUrl()).setHeaders(h).build();

        n.executeRequest(request, new AsyncCompletionHandlerAdapter() {

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
        n.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncParamPOSTTest() throws Throwable {
        AsyncHttpClient n = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());

        final CountDownLatch l = new CountDownLatch(1);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        h.add("Content-Type", "application/x-www-form-urlencoded");

        Map<String, Collection<String>> m = new HashMap<String, Collection<String>>();
        for (int i = 0; i < 5; i++) {
            m.put("param_" + i, Arrays.asList("value_" + i));
        }
        Request request = new RequestBuilder("POST").setUrl(getTargetUrl()).setHeaders(h).setParameters(m).build();
        n.executeRequest(request, new AsyncCompletionHandlerAdapter() {

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
        n.close();

    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncStatusHEADTest() throws Throwable {
        AsyncHttpClient n = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());

        final CountDownLatch l = new CountDownLatch(1);
        Request request = new RequestBuilder("HEAD").setUrl(getTargetUrl()).build();
        Response response = n.executeRequest(request, new AsyncCompletionHandlerAdapter() {

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

        try {
            String s = response.getResponseBody();
            Assert.assertEquals("",s);
        } catch (IllegalStateException ex) {
            fail();
        }

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
        n.close();

    }

    // TODO: fix test
    @Test(groups = {"standalone", "default_provider", "async"}, enabled = false)
    public void asyncStatusHEADContentLenghtTest() throws Throwable {
        AsyncHttpClient n = getAsyncHttpClient(new AsyncHttpClientConfig.Builder()
                .setRequestTimeoutInMs(120 * 1000).build());

        final CountDownLatch l = new CountDownLatch(1);
        Request request = new RequestBuilder("HEAD")
                .setUrl(getTargetUrl())
                .build();

        n.executeRequest(request, new AsyncCompletionHandlerAdapter() {
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
        n.close();

    }

    @Test(groups = {"online", "default_provider", "async"})
    public void asyncNullSchemeTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);

        try {
            c.prepareGet("www.sun.com").execute();
            Assert.fail();
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(true);
        }
        c.close();

    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoGetTransferEncodingTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        final CountDownLatch l = new CountDownLatch(1);

        c.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {

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
        c.close();

    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoGetHeadersTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        final CountDownLatch l = new CountDownLatch(1);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        h.add("Test1", "Test1");
        h.add("Test2", "Test2");
        h.add("Test3", "Test3");
        h.add("Test4", "Test4");
        h.add("Test5", "Test5");
        c.prepareGet(getTargetUrl()).setHeaders(h).execute(new AsyncCompletionHandlerAdapter() {

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
        c.close();

    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoGetCookieTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        final CountDownLatch l = new CountDownLatch(1);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        h.add("Test1", "Test1");
        h.add("Test2", "Test2");
        h.add("Test3", "Test3");
        h.add("Test4", "Test4");
        h.add("Test5", "Test5");

        final Cookie coo = new Cookie("/", "foo", "value", "/", -1, false);
        c.prepareGet(getTargetUrl()).setHeaders(h).addCookie(coo).execute(new AsyncCompletionHandlerAdapter() {

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
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostDefaultContentType() throws Throwable {

        AsyncHttpClient c = getAsyncHttpClient(null);
        final CountDownLatch l = new CountDownLatch(1);
        c.preparePost(getTargetUrl()).addParameter("foo", "bar").execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    assertEquals(response.getStatusCode(), 200);
                    FluentCaseInsensitiveStringsMap h = response.getHeaders();
                    assertEquals(h.getJoinedValue("X-Content-Type", ", "), "application/x-www-form-urlencoded");
                } finally {
                    l.countDown();
                }
                return response;
            }
        }).get();

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostBodyIsoTest() throws Throwable {

        AsyncHttpClient c = getAsyncHttpClient(null);
        Response r = c.preparePost(getTargetUrl()).addHeader("X-ISO", "true").setBody("\u017D\u017D\u017D\u017D\u017D\u017D").execute().get();
        assertEquals(r.getResponseBody().getBytes("ISO-8859-1"),"\u017D\u017D\u017D\u017D\u017D\u017D".getBytes("ISO-8859-1"));
        c.close();
    }
    
    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostBytesTest() throws Throwable {

        AsyncHttpClient c = getAsyncHttpClient(null);
        final CountDownLatch l = new CountDownLatch(1);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
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

        c.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

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
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostInputStreamTest() throws Throwable {

        AsyncHttpClient c = getAsyncHttpClient(null);
        final CountDownLatch l = new CountDownLatch(1);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
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

        c.preparePost(getTargetUrl()).setHeaders(h).setBody(is).execute(new AsyncCompletionHandlerAdapter() {

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
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPutInputStreamTest() throws Throwable {

        AsyncHttpClient c = getAsyncHttpClient(null);
        final CountDownLatch l = new CountDownLatch(1);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
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

        c.preparePut(getTargetUrl()).setHeaders(h).setBody(is).execute(new AsyncCompletionHandlerAdapter() {

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
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostEntityWriterTest() throws Throwable {

        AsyncHttpClient c = getAsyncHttpClient(null);
        final CountDownLatch l = new CountDownLatch(1);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
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

        c.preparePost(getTargetUrl()).setHeaders(h).setBody(new Request.EntityWriter() {

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
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostMultiPartTest() throws Throwable {

        AsyncHttpClient c = getAsyncHttpClient(null);
        final CountDownLatch l = new CountDownLatch(1);

        Part p = new StringPart("foo", "bar");

        c.preparePost(getTargetUrl()).addBodyPart(p).execute(new AsyncCompletionHandlerAdapter() {

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
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostBasicGZIPTest() throws Throwable {

        AsyncHttpClientConfig cf = new AsyncHttpClientConfig.Builder().setCompressionEnabled(true).build();
        AsyncHttpClient c = getAsyncHttpClient(cf);
        final CountDownLatch l = new CountDownLatch(1);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
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

        c.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

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
        c.close();

    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostProxyTest() throws Throwable {

        AsyncHttpClientConfig cf = new AsyncHttpClientConfig.Builder().setProxyServer(new ProxyServer("127.0.0.1", port2)).build();
        AsyncHttpClient c = getAsyncHttpClient(cf);

        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
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

        Response response = c.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandler<Response>() {
            @Override
            public Response onCompleted(Response response) throws Exception {
                return response;
            }

            @Override
            public void onThrowable(Throwable t) {
            }
        }).get();


        assertEquals(response.getStatusCode(), 200);
        assertEquals(response.getHeader("X-Proxy-Connection"), "keep-alive");
        c.close();
    }


    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncRequestVirtualServerPOSTTest() throws Throwable {
        AsyncHttpClient n = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());

        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        h.add("Content-Type", "application/x-www-form-urlencoded");

        Map<String, Collection<String>> m = new HashMap<String, Collection<String>>();
        for (int i = 0; i < 5; i++) {
            m.put("param_" + i, Arrays.asList("value_" + i));
        }
        Request request = new RequestBuilder("POST")
                .setUrl(getTargetUrl())
                .setHeaders(h)
                .setParameters(m)
                .setVirtualHost("localhost:" + port1)
                .build();

        Response response = n.executeRequest(request, new AsyncCompletionHandlerAdapter()).get();

        assertEquals(response.getStatusCode(), 200);
        if (response.getHeader("X-Host").startsWith("localhost")) {
            assertEquals(response.getHeader("X-Host"), "localhost:" + port1);
        } else {
            assertEquals(response.getHeader("X-Host"), "127.0.0.1:" + port1);
        }
        n.close();

    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPutTest() throws Throwable {

        AsyncHttpClient c = getAsyncHttpClient(null);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
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

        Response response = c.preparePut(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter()).get();

        assertEquals(response.getStatusCode(), 200);
        c.close();

    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostLatchBytesTest() throws Throwable {

        AsyncHttpClient c = getAsyncHttpClient(null);
        final CountDownLatch l = new CountDownLatch(1);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
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

        c.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

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
        c.close();

    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostDelayCancelTest() throws Throwable {

        AsyncHttpClient c = getAsyncHttpClient(null);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        h.add("LockThread", "true");
        StringBuilder sb = new StringBuilder();
        sb.append("LockThread=true");

        Future<Response> future = c.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter(){
            @Override
            public void onThrowable(Throwable t) {
            }
        });
        future.cancel(true);
        Response response = future.get(TIMEOUT, TimeUnit.SECONDS);
        Assert.assertNull(response);
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostDelayBytesTest() throws Throwable {

        AsyncHttpClient c = getAsyncHttpClient(null);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        h.add("LockThread", "true");
        StringBuilder sb = new StringBuilder();
        sb.append("LockThread=true");

        try {
            Future<Response> future = c.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {
                @Override
                public void onThrowable(Throwable t) {
                    t.printStackTrace();
                }
            });

            future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {            
            if (ex.getCause() != null && TimeoutException.class.isAssignableFrom(ex.getCause().getClass())) {
                Assert.assertTrue(true);
            }
        } catch (TimeoutException te) {
            Assert.assertTrue(true);
        } catch (IllegalStateException ex) {
            Assert.assertTrue(false);
        }
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostNullBytesTest() throws Throwable {

        AsyncHttpClient c = getAsyncHttpClient(null);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
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

        Future<Response> future = c.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter());

        Response response = future.get();
        Assert.assertNotNull(response);
        assertEquals(response.getStatusCode(), 200);
        c.close();

    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostListenerBytesTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
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

        c.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {
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
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncConnectInvalidFuture() throws Throwable {

        int dummyPort = findFreePort();
        AsyncHttpClient c = getAsyncHttpClient(null);
        final AtomicInteger count = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            try {
                Response response = c.preparePost(String.format("http://127.0.0.1:%d/", dummyPort)).execute(new AsyncCompletionHandlerAdapter() {
                    /* @Override */
                    public void onThrowable(Throwable t) {
                        count.incrementAndGet();
                    }
                }).get();
                assertNull(response, "Should have thrown ExecutionException");
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (!(cause instanceof ConnectException)) {
                    fail("Should have been caused by ConnectException, not by " + cause.getClass().getName());
                }
            }
        }
        assertEquals(count.get(), 20);
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncConnectInvalidPortFuture() throws Throwable {

        int dummyPort = findFreePort();
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            Response response = c.preparePost(String.format("http://127.0.0.1:%d/", dummyPort)).execute(new AsyncCompletionHandlerAdapter() {
                /* @Override */
                public void onThrowable(Throwable t) {
                    t.printStackTrace();
                }
            }).get();
            assertNull(response, "Should have thrown ExecutionException");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (!(cause instanceof ConnectException)) {
                fail("Should have been caused by ConnectException, not by " + cause.getClass().getName());
            }
        }
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncConnectInvalidPort() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);

        // pick a random unused local port
        int port = findFreePort();

        try {
            Response response = c.preparePost(String.format("http://127.0.0.1:%d/", port)).execute(new AsyncCompletionHandlerAdapter() {
                /* @Override */
                public void onThrowable(Throwable t) {
                    t.printStackTrace();
                }
            }).get();
            assertNull(response, "No ExecutionException was thrown");
        } catch (ExecutionException ex) {
            assertEquals(ex.getCause().getClass(), ConnectException.class);
        }
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncConnectInvalidHandlerPort() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        final CountDownLatch l = new CountDownLatch(1);
        int port = findFreePort();

        c.prepareGet(String.format("http://127.0.0.1:%d/", port)).execute(new AsyncCompletionHandlerAdapter() {
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
        c.close();
    }

    @Test(groups = {"online", "default_provider", "async"})
    public void asyncConnectInvalidHandlerHost() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        final CountDownLatch l = new CountDownLatch(1);

        c.prepareGet("http://null.apache.org:9999/").execute(new AsyncCompletionHandlerAdapter() {
            /* @Override */
            public void onThrowable(Throwable t) {
                if (t != null) {
                    if (t.getClass().equals(ConnectException.class)) {
                        l.countDown();
                    } else if (t.getClass().equals(UnresolvedAddressException.class)) {
                        l.countDown();
                    }
                }
            }
        });

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
        c.close();
    }


    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncConnectInvalidFuturePort() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);

        final AtomicBoolean called = new AtomicBoolean(false);
        final AtomicBoolean rightCause = new AtomicBoolean(false);
        // pick a random unused local port
        int port = findFreePort();

        try {
            Response response = c.prepareGet(String.format("http://127.0.0.1:%d/", port)).execute(new AsyncCompletionHandlerAdapter() {
                @Override
                public void onThrowable(Throwable t) {
                    called.set(true);
                    if (t instanceof ConnectException) {
                        rightCause.set(true);
                    }
                }
            }).get();
            assertNull(response, "No ExecutionException was thrown");
        } catch (ExecutionException ex) {
            assertEquals(ex.getCause().getClass(), ConnectException.class);
        }
        assertTrue(called.get(), "onThrowable should get called.");
        assertTrue(rightCause.get(), "onThrowable should get called with ConnectionException");
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncContentLenghtGETTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        Response response = c.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public void onThrowable(Throwable t) {
                Assert.fail("Unexpected exception", t);
            }
        }).get();

        Assert.assertNotNull(response);
        assertEquals(response.getStatusCode(), 200);
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncResponseBodyTooLarge() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        Response response = c.preparePost(getTargetUrl()).setBody("0123456789").execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public void onThrowable(Throwable t) {
                Assert.fail("Unexpected exception", t);
            }
        }).get();

        Assert.assertNotNull(response.getResponseBodyExcerpt(Integer.MAX_VALUE));
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncResponseEmptyBody() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        Response response = c.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public void onThrowable(Throwable t) {
                Assert.fail("Unexpected exception", t);
            }
        }).get();

        assertEquals(response.getResponseBody(),"");
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "asyncAPI"})
    public void asyncAPIContentLenghtGETTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(1);

        client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {

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
        client.close();
    }

    @Test(groups = {"standalone", "default_provider", "asyncAPI"})
    public void asyncAPIHandlerExceptionTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(1);

        client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {
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
        client.close();

    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoGetDelayHandlerTest() throws Throwable {
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        h.add("LockThread", "true");
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(5 * 1000).build());

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(1);

        client.prepareGet(getTargetUrl()).setHeaders(h).execute(new AsyncCompletionHandlerAdapter() {

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
        client.close();

    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoGetQueryStringTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);

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

        Request req = new RequestBuilder("GET")
                .setUrl(getTargetUrl() + "?foo=bar").build();

        client.executeRequest(req, handler).get();

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
        client.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoGetKeepAliveHandlerTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(2);

        AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

            String remoteAddr = null;

            @Override
            public Response onCompleted(Response response) throws Exception {
                assertEquals(response.getStatusCode(), 200);
                if (remoteAddr == null) {
                    remoteAddr = response.getHeader("X-KEEP-ALIVE");
                    l.countDown();
                } else {
                    assertEquals(response.getHeader("X-KEEP-ALIVE"), remoteAddr);
                    l.countDown();
                }

                return response;
            }
        };

        client.prepareGet(getTargetUrl()).execute(handler).get();
        client.prepareGet(getTargetUrl()).execute(handler);

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
        client.close();
    }

    @Test(groups = {"online", "default_provider", "async"})
    public void asyncDoGetMaxRedirectTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(new Builder().setMaximumNumberOfRedirects(0).setFollowRedirects(true).build());

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

    @Test(groups = {"online", "default_provider", "async"})
    public void asyncDoGetNestedTest() throws Throwable {
        final AsyncHttpClient client = getAsyncHttpClient(new Builder().build());

        // Use a l in case the assert fail
        final CountDownLatch l = new CountDownLatch(2);

        final AsyncCompletionHandlerAdapter handler = new AsyncCompletionHandlerAdapter() {

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
                t.printStackTrace();
            }
        };


        client.prepareGet("http://www.google.com/").execute(handler);

        if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
        client.close();
    }

    @Test(groups = {"online", "default_provider", "async"})
    public void asyncDoGetStreamAndBodyTest() throws Throwable {
        final AsyncHttpClient client = getAsyncHttpClient(new Builder().build());
        Response r = client.prepareGet("http://www.google.com/").execute().get();

        r.getResponseBody();
        r.getResponseBodyAsStream();

        client.close();
    }

    @Test(groups = {"online", "default_provider", "async"})
    public void asyncUrlWithoutPathTest() throws Throwable {
        final AsyncHttpClient client = getAsyncHttpClient(new Builder().build());
        Response r = client.prepareGet("http://www.google.com").execute().get();

        r.getResponseBody();
        r.getResponseBodyAsStream();

        client.close();
    }

    @Test(groups = {"default_provider", "async"})
    public void optionsTest() throws Throwable {
        final AsyncHttpClient client = getAsyncHttpClient(new Builder().build());
        Response r = client.prepareOptions(getTargetUrl()).execute().get();

        assertEquals(r.getStatusCode(), 200);
        assertEquals(r.getHeader("Allow"), "GET,HEAD,POST,OPTIONS,TRACE");

        client.close();
    }

    @Test(groups = {"online", "default_provider"})
    public void testAwsS3() throws Exception {
        final AsyncHttpClient c = getAsyncHttpClient(new Builder().build());
        Response response = c.prepareGet("http://test.s3.amazonaws.com/").execute().get();
        if (response.getResponseBody() == null || response.getResponseBody().equals("")) {
            fail("No response Body");
        } else {
            assertEquals(response.getStatusCode(), 403);
        }
        c.close();
    }

    @Test(groups = {"online", "default_provider"})
    public void testAsyncHttpProviderConfig() throws Exception {

        final AsyncHttpClient c = getAsyncHttpClient(new Builder().setAsyncHttpClientProviderConfig(getProviderConfig()).build());
        Response response = c.prepareGet("http://test.s3.amazonaws.com/").execute().get();
        if (response.getResponseBody() == null || response.getResponseBody().equals("")) {
            fail("No response Body");
        } else {
            assertEquals(response.getStatusCode(), 403);
        }
        c.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void idleRequestTimeoutTest() throws Exception {
        AsyncHttpClient c = getAsyncHttpClient(
                new AsyncHttpClientConfig.Builder().setIdleConnectionInPoolTimeoutInMs(5000).setRequestTimeoutInMs(10000).build());
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        h.add("LockThread", "true");

        long t1 = System.currentTimeMillis();
        try {
            c.prepareGet(getTargetUrl()).setHeaders(h).setUrl(getTargetUrl()).execute(new AsyncHandlerAdapter() {

                /* @Override */
                public void onThrowable(Throwable t) {
//                    t.printStackTrace();
                }

            }).get();
            Assert.fail();
        } catch (Throwable ex) {
            final long elapsedTime = System.currentTimeMillis() - t1;
            System.out.println("EXPIRED: " + (elapsedTime));
            Assert.assertNotNull(ex.getCause());
            Assert.assertTrue(elapsedTime >= 10000 && elapsedTime <= 25000);
        }
        c.close();
    }

    @Test(groups = {"standalone", "default_provider", "async"})
    public void asyncDoPostCancelTest() throws Throwable {

        AsyncHttpClient c = getAsyncHttpClient(null);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        h.add("Content-Type", "application/x-www-form-urlencoded");
        h.add("LockThread", "true");
        StringBuilder sb = new StringBuilder();
        sb.append("LockThread=true");

        final AtomicReference<CancellationException> ex = new AtomicReference<CancellationException>();
        ex.set(null);
        try {
            Future<Response> future = c.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public void onThrowable(Throwable t) {
                    if (t instanceof CancellationException) {
                        ex.set((CancellationException)t);
                    }
                    t.printStackTrace();
                }
                
            });

            future.cancel(true);
        } catch (IllegalStateException ise) {
            fail();
        }
        Assert.assertNotNull(ex.get());
        c.close();
    }

    @Test(groups = {"standalone", "default_provider"}, expectedExceptions = IllegalArgumentException.class)
    public void getShouldNotAllowBody() throws IllegalArgumentException, IOException {
        AsyncHttpClient c = getAsyncHttpClient(null);
        AsyncHttpClient.BoundRequestBuilder builder = c.prepareGet(getTargetUrl());
        builder.setBody("Boo!");
        builder.execute();
    }

    @Test(groups = {"standalone", "default_provider"}, expectedExceptions = IllegalArgumentException.class)
    public void headShouldNotAllowBody() throws IllegalArgumentException, IOException {
        AsyncHttpClient c = getAsyncHttpClient(null);
        AsyncHttpClient.BoundRequestBuilder builder = c.prepareHead(getTargetUrl());
        builder.setBody("Boo!");
        builder.execute();
    }

    protected String getBrokenTargetUrl() {
        return String.format("http:127.0.0.1:%d/foo/test", port1);
    }

    @Test(groups = {"standalone", "default_provider"})
    public void invalidUri() throws Exception {
        AsyncHttpClient c = getAsyncHttpClient(null);
        AsyncHttpClient.BoundRequestBuilder builder = c.prepareGet(getBrokenTargetUrl());
        Response r = c.executeRequest(builder.build()).get();
        assertEquals(200, r.getStatusCode());
    }

    @Test(groups = {"standalone", "default_provider"})
    public void asyncHttpClientConfigBeanTest() throws Exception {
        AsyncHttpClient c = getAsyncHttpClient(new AsyncHttpClientConfigBean().setUserAgent("test"));
        AsyncHttpClient.BoundRequestBuilder builder = c.prepareGet(getTargetUrl());
        Response r = c.executeRequest(builder.build()).get();
        assertEquals(200, r.getStatusCode());
    }

    @Test(groups = {"default_provider", "async"})
    public void bodyAsByteTest() throws Throwable {
        final AsyncHttpClient client = getAsyncHttpClient(new Builder().build());
        Response r = client.prepareGet(getTargetUrl()).execute().get();

        assertEquals(r.getStatusCode(), 200);
        assertEquals(r.getResponseBodyAsBytes(), new byte[]{});

        client.close();
    }

    @Test(groups = {"default_provider", "async"})
    public void mirrorByteTest() throws Throwable {
        final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        Response r = client.preparePost(getTargetUrl()).setBody("MIRROR").execute().get();

        assertEquals(r.getStatusCode(), 200);
        assertEquals(new String(r.getResponseBodyAsBytes(), "UTF-8"), "MIRROR");

        client.close();
    }

    protected abstract AsyncHttpProviderConfig getProviderConfig();
}
