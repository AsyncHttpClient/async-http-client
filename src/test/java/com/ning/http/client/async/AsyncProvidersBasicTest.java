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

import static com.ning.http.util.DateUtil.millisTime;
import static com.ning.http.util.MiscUtil.isNonEmpty;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

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

import org.testng.Assert;
import org.testng.annotations.Test;

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

public abstract class AsyncProvidersBasicTest extends AbstractBasicTest {
    private static final String UTF_8 = "text/html;charset=UTF-8";

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncProviderEncodingTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Request request = new RequestBuilder("GET").setUrl(getTargetUrl() + "?q=+%20x").build();
            String requestUrl = request.getUrl();
            Assert.assertEquals(requestUrl, getTargetUrl() + "?q=%20%20x");
            Future<String> responseFuture = client.executeRequest(request, new AsyncCompletionHandler<String>() {
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
            Assert.assertEquals(url, getTargetUrl() + "?q=%20%20x");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncProviderEncodingTest2() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Request request = new RequestBuilder("GET").setUrl(getTargetUrl() + "").addQueryParameter("q", "a b").build();

            Future<String> responseFuture = client.executeRequest(request, new AsyncCompletionHandler<String>() {
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
            Assert.assertEquals(url, getTargetUrl() + "?q=a%20b");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void emptyRequestURI() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Request request = new RequestBuilder("GET").setUrl(getTargetUrl()).build();

            Future<String> responseFuture = client.executeRequest(request, new AsyncCompletionHandler<String>() {
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
            Assert.assertEquals(url, getTargetUrl());
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncProviderContentLenghtGETTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            URL url = new URL(getTargetUrl());
            final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.connect();

            Request request = new RequestBuilder("GET").setUrl(getTargetUrl()).build();
            client.executeRequest(request, new AsyncCompletionHandlerAdapter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncContentTypeGETTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = new RequestBuilder("GET").setUrl(getTargetUrl()).build();
            client.executeRequest(request, new AsyncCompletionHandlerAdapter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncHeaderGETTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = new RequestBuilder("GET").setUrl(getTargetUrl()).build();
            client.executeRequest(request, new AsyncCompletionHandlerAdapter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncHeaderPOSTTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Test1", "Test1");
            h.add("Test2", "Test2");
            h.add("Test3", "Test3");
            h.add("Test4", "Test4");
            h.add("Test5", "Test5");
            Request request = new RequestBuilder("GET").setUrl(getTargetUrl()).setHeaders(h).build();

            client.executeRequest(request, new AsyncCompletionHandlerAdapter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncParamPOSTTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");

            Map<String, Collection<String>> m = new HashMap<String, Collection<String>>();
            for (int i = 0; i < 5; i++) {
                m.put("param_" + i, Arrays.asList("value_" + i));
            }
            Request request = new RequestBuilder("POST").setUrl(getTargetUrl()).setHeaders(h).setParameters(m).build();
            client.executeRequest(request, new AsyncCompletionHandlerAdapter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncStatusHEADTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = new RequestBuilder("HEAD").setUrl(getTargetUrl()).build();
            Response response = client.executeRequest(request, new AsyncCompletionHandlerAdapter() {

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
                Assert.assertEquals("", s);
            } catch (IllegalStateException ex) {
                fail();
            }

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                Assert.fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    // TODO: fix test
    @Test(groups = { "standalone", "default_provider", "async" }, enabled = false)
    public void asyncStatusHEADContentLenghtTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(120 * 1000).build());
        try {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = new RequestBuilder("HEAD").setUrl(getTargetUrl()).build();

            client.executeRequest(request, new AsyncCompletionHandlerAdapter() {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider", "async" })
    public void asyncNullSchemeTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            client.prepareGet("www.sun.com").execute();
            Assert.fail();
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(true);
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoGetTransferEncodingTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);

            client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoGetHeadersTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Test1", "Test1");
            h.add("Test2", "Test2");
            h.add("Test3", "Test3");
            h.add("Test4", "Test4");
            h.add("Test5", "Test5");
            client.prepareGet(getTargetUrl()).setHeaders(h).execute(new AsyncCompletionHandlerAdapter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoGetCookieTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Test1", "Test1");
            h.add("Test2", "Test2");
            h.add("Test3", "Test3");
            h.add("Test4", "Test4");
            h.add("Test5", "Test5");

            final Cookie coo = new Cookie("/", "foo", "value", "/", -1, false);
            client.prepareGet(getTargetUrl()).setHeaders(h).addCookie(coo).execute(new AsyncCompletionHandlerAdapter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostDefaultContentType() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            client.preparePost(getTargetUrl()).addParameter("foo", "bar").execute(new AsyncCompletionHandlerAdapter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostBodyIsoTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response r = client.preparePost(getTargetUrl()).addHeader("X-ISO", "true").setBody("\u017D\u017D\u017D\u017D\u017D\u017D").execute().get();
            assertEquals(r.getResponseBody().getBytes("ISO-8859-1"), "\u017D\u017D\u017D\u017D\u017D\u017D".getBytes("ISO-8859-1"));
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostBytesTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
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
            sb.setLength(sb.length() - 1);

            client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostInputStreamTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
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
            sb.setLength(sb.length() - 1);
            ByteArrayInputStream is = new ByteArrayInputStream(sb.toString().getBytes());

            client.preparePost(getTargetUrl()).setHeaders(h).setBody(is).execute(new AsyncCompletionHandlerAdapter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPutInputStreamTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
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
            sb.setLength(sb.length() - 1);
            ByteArrayInputStream is = new ByteArrayInputStream(sb.toString().getBytes());

            client.preparePut(getTargetUrl()).setHeaders(h).setBody(is).execute(new AsyncCompletionHandlerAdapter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostEntityWriterTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
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
            sb.setLength(sb.length() - 1);
            byte[] bytes = sb.toString().getBytes();
            h.add("Content-Length", String.valueOf(bytes.length));

            client.preparePost(getTargetUrl()).setHeaders(h).setBody(new Request.EntityWriter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostMultiPartTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);

            Part p = new StringPart("foo", "bar");

            client.preparePost(getTargetUrl()).addBodyPart(p).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        String xContentType = response.getHeader("X-Content-Type");
                        String boundary = xContentType.substring((xContentType.indexOf("boundary") + "boundary".length() + 1));

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostBasicGZIPTest() throws Throwable {
        AsyncHttpClientConfig cf = new AsyncHttpClientConfig.Builder().setCompressionEnabled(true).build();
        AsyncHttpClient client = getAsyncHttpClient(cf);
        try {
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
            sb.setLength(sb.length() - 1);

            client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getStatusCode(), 200);
                        assertEquals(response.getHeader("X-Accept-Encoding"), "gzip,deflate");
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            }).get();
            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                Assert.fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostProxyTest() throws Throwable {
        AsyncHttpClientConfig cf = new AsyncHttpClientConfig.Builder().setProxyServer(new ProxyServer("127.0.0.1", port2)).build();
        AsyncHttpClient client = getAsyncHttpClient(cf);
        try {
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
            sb.setLength(sb.length() - 1);

            Response response = client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandler<Response>() {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncRequestVirtualServerPOSTTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");

            Map<String, Collection<String>> m = new HashMap<String, Collection<String>>();
            for (int i = 0; i < 5; i++) {
                m.put("param_" + i, Arrays.asList("value_" + i));
            }
            Request request = new RequestBuilder("POST").setUrl(getTargetUrl()).setHeaders(h).setParameters(m).setVirtualHost("localhost:" + port1).build();

            Response response = client.executeRequest(request, new AsyncCompletionHandlerAdapter()).get();

            assertEquals(response.getStatusCode(), 200);
            if (response.getHeader("X-Host").startsWith("localhost")) {
                assertEquals(response.getHeader("X-Host"), "localhost:" + port1);
            } else {
                assertEquals(response.getHeader("X-Host"), "127.0.0.1:" + port1);
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPutTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
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
            sb.setLength(sb.length() - 1);

            Response response = client.preparePut(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter()).get();

            assertEquals(response.getStatusCode(), 200);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostLatchBytesTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
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
            sb.setLength(sb.length() - 1);

            client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostDelayCancelTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            h.add("LockThread", "true");
            StringBuilder sb = new StringBuilder();
            sb.append("LockThread=true");

            Future<Response> future = client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {
                @Override
                public void onThrowable(Throwable t) {
                }
            });

            // Make sure we are connected before cancelling. I know, Thread.sleep
            // sucks!
            Thread.sleep(1000);
            future.cancel(true);
            Response response = future.get(TIMEOUT, TimeUnit.SECONDS);
            Assert.assertNull(response);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostDelayBytesTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            h.add("LockThread", "true");
            StringBuilder sb = new StringBuilder();
            sb.append("LockThread=true");

            try {
                Future<Response> future = client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {
                    @Override
                    public void onThrowable(Throwable t) {
                        t.printStackTrace();
                    }
                });

                future.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof TimeoutException) {
                    Assert.assertTrue(true);
                }
            } catch (TimeoutException te) {
                Assert.assertTrue(true);
            } catch (IllegalStateException ex) {
                Assert.assertTrue(false);
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostNullBytesTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
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
            sb.setLength(sb.length() - 1);

            Future<Response> future = client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter());

            Response response = future.get();
            Assert.assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostListenerBytesTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
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
            sb.setLength(sb.length() - 1);

            final CountDownLatch l = new CountDownLatch(1);

            client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncConnectInvalidFuture() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            int dummyPort = findFreePort();
            final AtomicInteger count = new AtomicInteger();
            for (int i = 0; i < 20; i++) {
                try {
                    Response response = client.preparePost(String.format("http://127.0.0.1:%d/", dummyPort)).execute(new AsyncCompletionHandlerAdapter() {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncConnectInvalidPortFuture() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            int dummyPort = findFreePort();
            try {
                Response response = client.preparePost(String.format("http://127.0.0.1:%d/", dummyPort)).execute(new AsyncCompletionHandlerAdapter() {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncConnectInvalidPort() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            // pick a random unused local port
            int port = findFreePort();

            try {
                Response response = client.preparePost(String.format("http://127.0.0.1:%d/", port)).execute(new AsyncCompletionHandlerAdapter() {
                    /* @Override */
                    public void onThrowable(Throwable t) {
                        t.printStackTrace();
                    }
                }).get();
                assertNull(response, "No ExecutionException was thrown");
            } catch (ExecutionException ex) {
                assertEquals(ex.getCause().getClass(), ConnectException.class);
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncConnectInvalidHandlerPort() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            int port = findFreePort();

            client.prepareGet(String.format("http://127.0.0.1:%d/", port)).execute(new AsyncCompletionHandlerAdapter() {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider", "async" })
    public void asyncConnectInvalidHandlerHost() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);

            client.prepareGet("http://null.apache.org:9999/").execute(new AsyncCompletionHandlerAdapter() {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncConnectInvalidFuturePort() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final AtomicBoolean called = new AtomicBoolean(false);
            final AtomicBoolean rightCause = new AtomicBoolean(false);
            // pick a random unused local port
            int port = findFreePort();

            try {
                Response response = client.prepareGet(String.format("http://127.0.0.1:%d/", port)).execute(new AsyncCompletionHandlerAdapter() {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncContentLenghtGETTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public void onThrowable(Throwable t) {
                    Assert.fail("Unexpected exception", t);
                }
            }).get();

            Assert.assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncResponseBodyTooLarge() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.preparePost(getTargetUrl()).setBody("0123456789").execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public void onThrowable(Throwable t) {
                    Assert.fail("Unexpected exception", t);
                }
            }).get();

            Assert.assertNotNull(response.getResponseBodyExcerpt(Integer.MAX_VALUE));
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncResponseEmptyBody() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public void onThrowable(Throwable t) {
                    Assert.fail("Unexpected exception", t);
                }
            }).get();

            assertEquals(response.getResponseBody(), "");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "asyncAPI" })
    public void asyncAPIContentLenghtGETTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "asyncAPI" })
    public void asyncAPIHandlerExceptionTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoGetDelayHandlerTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(5 * 1000).build());
        try {
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("LockThread", "true");

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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoGetQueryStringTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
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

            Request req = new RequestBuilder("GET").setUrl(getTargetUrl() + "?foo=bar").build();

            client.executeRequest(req, handler).get();

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                Assert.fail("Timed out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoGetKeepAliveHandlerTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider", "async" })
    public void asyncDoGetMaxRedirectTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(new Builder().setMaximumNumberOfRedirects(0).setFollowRedirects(true).build());
        try {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider", "async" })
    public void asyncDoGetNestedTest() throws Throwable {
        final AsyncHttpClient client = getAsyncHttpClient(new Builder().build());
        try {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider", "async" })
    public void asyncDoGetStreamAndBodyTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(new Builder().build());
        try {
            Response r = client.prepareGet("http://www.google.com/").execute().get();

            r.getResponseBody();
            r.getResponseBodyAsStream();
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider", "async" })
    public void asyncUrlWithoutPathTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(new Builder().build());
        try {
            Response r = client.prepareGet("http://www.google.com").execute().get();

            r.getResponseBody();
            r.getResponseBodyAsStream();
        } finally {
            client.close();
        }
    }

    @Test(groups = { "default_provider", "async" })
    public void optionsTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(new Builder().build());
        try {
            Response r = client.prepareOptions(getTargetUrl()).execute().get();

            assertEquals(r.getStatusCode(), 200);
            assertEquals(r.getHeader("Allow"), "GET,HEAD,POST,OPTIONS,TRACE");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void testAwsS3() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(new Builder().build());
        try {
            Response response = client.prepareGet("http://test.s3.amazonaws.com/").execute().get();
            if (!isNonEmpty(response.getResponseBody())) {
                fail("No response Body");
            } else {
                assertEquals(response.getStatusCode(), 403);
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void testAsyncHttpProviderConfig() throws Exception {

        AsyncHttpClient client = getAsyncHttpClient(new Builder().setAsyncHttpClientProviderConfig(getProviderConfig()).build());
        try {
            Response response = client.prepareGet("http://test.s3.amazonaws.com/").execute().get();
            if (!isNonEmpty(response.getResponseBody())) {
                fail("No response Body");
            } else {
                assertEquals(response.getStatusCode(), 403);
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void idleRequestTimeoutTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setIdleConnectionInPoolTimeoutInMs(5000).setRequestTimeoutInMs(10000).build());
        try {
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            h.add("LockThread", "true");

            long t1 = millisTime();
            try {
                client.prepareGet(getTargetUrl()).setHeaders(h).setUrl(getTargetUrl()).execute(new AsyncHandlerAdapter() {

                    /* @Override */
                    public void onThrowable(Throwable t) {
                        // t.printStackTrace();
                    }

                }).get();
                Assert.fail();
            } catch (Throwable ex) {
                final long elapsedTime = millisTime() - t1;
                System.out.println("EXPIRED: " + (elapsedTime));
                Assert.assertNotNull(ex.getCause());
                Assert.assertTrue(elapsedTime >= 10000 && elapsedTime <= 25000);
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostCancelTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            h.add("LockThread", "true");
            StringBuilder sb = new StringBuilder();
            sb.append("LockThread=true");

            final AtomicReference<CancellationException> ex = new AtomicReference<CancellationException>();
            ex.set(null);
            try {
                Future<Response> future = client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

                    @Override
                    public void onThrowable(Throwable t) {
                        if (t instanceof CancellationException) {
                            ex.set((CancellationException) t);
                        }
                        t.printStackTrace();
                    }

                });

                Thread.sleep(1000);
                future.cancel(true);
            } catch (IllegalStateException ise) {
                fail();
            }
            Assert.assertNotNull(ex.get());
        } finally {
            client.close();
        }
    }

    protected String getBrokenTargetUrl() {
        return String.format("http:127.0.0.1:%d/foo/test", port1);
    }

    @Test(groups = { "standalone", "default_provider" }, expectedExceptions = { IllegalArgumentException.class })
    public void invalidUri() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            client.prepareGet(getBrokenTargetUrl());
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void asyncHttpClientConfigBeanTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfigBean().setUserAgent("test"));
        try {
            AsyncHttpClient.BoundRequestBuilder builder = client.prepareGet(getTargetUrl());
            Response r = client.executeRequest(builder.build()).get();
            assertEquals(200, r.getStatusCode());
        } finally {
            client.close();
        }
    }

    @Test(groups = { "default_provider", "async" })
    public void bodyAsByteTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(new Builder().build());
        try {
            Response r = client.prepareGet(getTargetUrl()).execute().get();

            assertEquals(r.getStatusCode(), 200);
            assertEquals(r.getResponseBodyAsBytes(), new byte[] {});
        } finally {
            client.close();
        }
    }

    @Test(groups = { "default_provider", "async" })
    public void mirrorByteTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response r = client.preparePost(getTargetUrl()).setBody("MIRROR").execute().get();

            assertEquals(r.getStatusCode(), 200);
            assertEquals(new String(r.getResponseBodyAsBytes(), "UTF-8"), "MIRROR");
        } finally {
            client.close();
        }
    }

    protected abstract AsyncHttpProviderConfig<?, ?> getProviderConfig();
}
