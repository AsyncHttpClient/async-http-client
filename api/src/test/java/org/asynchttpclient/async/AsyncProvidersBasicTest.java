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
package org.asynchttpclient.async;

import static org.asynchttpclient.async.util.TestUtils.*;
import static org.asynchttpclient.util.DateUtil.millisTime;
import static org.testng.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpClientConfig.Builder;
import org.asynchttpclient.AsyncHttpClientConfigBean;
import org.asynchttpclient.AsyncHttpProviderConfig;
import org.asynchttpclient.Cookie;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.MaxRedirectException;
import org.asynchttpclient.Part;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.StringPart;
import org.testng.annotations.Test;

public abstract class AsyncProvidersBasicTest extends AbstractBasicTest {

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncProviderEncodingTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Request request = new RequestBuilder("GET").setUrl(getTargetUrl() + "?q=+%20x").build();
            assertEquals(request.getUrl(), getTargetUrl() + "?q=%20%20x");

            String url = client.executeRequest(request, new AsyncCompletionHandler<String>() {
                @Override
                public String onCompleted(Response response) throws Exception {
                    return response.getUri().toString();
                }

                @Override
                public void onThrowable(Throwable t) {
                    t.printStackTrace();
                    fail("Unexpected exception: " + t.getMessage(), t);
                }

            }).get();
            assertEquals(url, getTargetUrl() + "?q=%20%20x");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncProviderEncodingTest2() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Request request = new RequestBuilder("GET").setUrl(getTargetUrl() + "").addQueryParameter("q", "a b").build();

            String url = client.executeRequest(request, new AsyncCompletionHandler<String>() {
                @Override
                public String onCompleted(Response response) throws Exception {
                    return response.getUri().toString();
                }

                @Override
                public void onThrowable(Throwable t) {
                    t.printStackTrace();
                    fail("Unexpected exception: " + t.getMessage(), t);
                }

            }).get();
            assertEquals(url, getTargetUrl() + "?q=a%20b");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void emptyRequestURI() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Request request = new RequestBuilder("GET").setUrl(getTargetUrl()).build();

            String url = client.executeRequest(request, new AsyncCompletionHandler<String>() {
                @Override
                public String onCompleted(Response response) throws Exception {
                    return response.getUri().toString();
                }

                @Override
                public void onThrowable(Throwable t) {
                    t.printStackTrace();
                    fail("Unexpected exception: " + t.getMessage(), t);
                }

            }).get();
            assertEquals(url, getTargetUrl());
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncProviderContentLenghtGETTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        final HttpURLConnection connection = (HttpURLConnection) new URL(getTargetUrl()).openConnection();
        connection.connect();
        final int ct = connection.getContentLength();
        connection.disconnect();
        try {
            final CountDownLatch l = new CountDownLatch(1);

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
                        assertEquals(contentLenght, ct);
                    } finally {
                        l.countDown();
                    }
                    return response;
                }

                @Override
                public void onThrowable(Throwable t) {
                    try {
                        fail("Unexpected exception", t);
                    } finally {
                        l.countDown();
                    }
                }

            }).get();

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncContentTypeGETTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = new RequestBuilder("GET").setUrl(getTargetUrl()).build();
            client.executeRequest(request, new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getStatusCode(), 200);
                        assertEquals(response.getContentType(), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            }).get();
            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncHeaderGETTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = new RequestBuilder("GET").setUrl(getTargetUrl()).build();
            client.executeRequest(request, new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getStatusCode(), 200);
                        assertEquals(response.getContentType(), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            }).get();

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncHeaderPOSTTest() throws Exception {
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
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncParamPOSTTest() throws Exception {
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
                            assertEquals(response.getHeader("X-param_" + i), "value_" + i);
                        }
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            }).get();

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncStatusHEADTest() throws Exception {
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
                assertEquals("", s);
            } catch (IllegalStateException ex) {
                fail();
            }

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    // TODO: fix test
    @Test(groups = { "standalone", "default_provider", "async" }, enabled = false)
    public void asyncStatusHEADContentLenghtTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(120 * 1000).build());
        try {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = new RequestBuilder("HEAD").setUrl(getTargetUrl()).build();

            client.executeRequest(request, new AsyncCompletionHandlerAdapter() {
                @Override
                public Response onCompleted(Response response) throws Exception {
                    fail();
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
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider", "async" })
    public void asyncNullSchemeTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);

        try {
            client.prepareGet("www.sun.com").execute();
            fail();
        } catch (IllegalArgumentException ex) {
            assertTrue(true);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoGetTransferEncodingTest() throws Exception {
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
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoGetHeadersTest() throws Exception {
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
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoGetCookieTest() throws Exception {
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
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostDefaultContentType() throws Exception {
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
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostBodyIsoTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.preparePost(getTargetUrl()).addHeader("X-ISO", "true").setBody("\u017D\u017D\u017D\u017D\u017D\u017D").execute().get();
            assertEquals(response.getResponseBody().getBytes("ISO-8859-1"), "\u017D\u017D\u017D\u017D\u017D\u017D".getBytes("ISO-8859-1"));
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostBytesTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_").append(i).append("=value_").append(i).append("&");
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
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostInputStreamTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_").append(i).append("=value_").append(i).append("&");
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
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPutInputStreamTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_").append(i).append("=value_").append(i).append("&");
            }
            sb.setLength(sb.length() - 1);
            ByteArrayInputStream is = new ByteArrayInputStream(sb.toString().getBytes());

            client.preparePut(getTargetUrl()).setHeaders(h).setBody(is).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getStatusCode(), 200);
                        for (int i = 1; i < 5; i++) {
                            assertEquals(response.getHeader("X-param_" + i), "value_" + i);
                        }
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            }).get();
            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostMultiPartTest() throws Exception {
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
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostBasicGZIPTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setCompressionEnabled(true).build());
        try {
            final CountDownLatch l = new CountDownLatch(1);
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_").append(i).append("=value_").append(i).append("&");
            }
            sb.setLength(sb.length() - 1);

            client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

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
                fail("Timeout out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostProxyTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setProxyServer(new ProxyServer("127.0.0.1", port2)).build());
        try {
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_").append(i).append("=value_").append(i).append("&");
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
    public void asyncRequestVirtualServerPOSTTest() throws Exception {
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
    public void asyncDoPutTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_").append(i).append("=value_").append(i).append("&");
            }
            sb.setLength(sb.length() - 1);

            Response response = client.preparePut(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter()).get();

            assertEquals(response.getStatusCode(), 200);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostLatchBytesTest() throws Exception {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_").append(i).append("=value_").append(i).append("&");
            }
            sb.setLength(sb.length() - 1);

            c.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getStatusCode(), 200);
                        for (int i = 1; i < 5; i++) {
                            assertEquals(response.getHeader("X-param_" + i), "value_" + i);
                        }
                        return response;
                    } finally {
                        l.countDown();
                    }
                }
            });

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        } finally {
            c.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostDelayCancelTest() throws Exception {
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
            future.cancel(true);
            Response response = future.get(TIMEOUT, TimeUnit.SECONDS);
            assertNull(response);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostDelayBytesTest() throws Exception {
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
                    assertTrue(true);
                }
            } catch (TimeoutException te) {
                assertTrue(true);
            } catch (IllegalStateException ex) {
                assertTrue(false);
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostNullBytesTest() throws Exception {

        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_").append(i).append("=value_").append(i).append("&");
            }
            sb.setLength(sb.length() - 1);

            Future<Response> future = client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter());

            Response response = future.get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostListenerBytesTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
            h.add("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_").append(i).append("=value_").append(i).append("&");
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
                fail("Latch time out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncConnectInvalidFuture() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            int dummyPort = findFreePort();
            final AtomicInteger count = new AtomicInteger();
            for (int i = 0; i < 20; i++) {
                try {
                    Response response = client.preparePost(String.format("http://127.0.0.1:%d/", dummyPort)).execute(new AsyncCompletionHandlerAdapter() {
                        @Override
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
    public void asyncConnectInvalidPortFuture() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            int dummyPort = findFreePort();
            try {
                Response response = client.preparePost(String.format("http://127.0.0.1:%d/", dummyPort)).execute(new AsyncCompletionHandlerAdapter() {
                    @Override
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
    public void asyncConnectInvalidPort() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            // pick a random unused local port
            int port = findFreePort();

            try {
                Response response = client.preparePost(String.format("http://127.0.0.1:%d/", port)).execute(new AsyncCompletionHandlerAdapter() {
                    @Override
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
    public void asyncConnectInvalidHandlerPort() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);
            int port = findFreePort();

            client.prepareGet(String.format("http://127.0.0.1:%d/", port)).execute(new AsyncCompletionHandlerAdapter() {
                @Override
                public void onThrowable(Throwable t) {
                    try {
                        assertEquals(t.getClass(), ConnectException.class);
                    } finally {
                        l.countDown();
                    }
                }
            });

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timed out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider", "async" })
    public void asyncConnectInvalidHandlerHost() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final CountDownLatch l = new CountDownLatch(1);

            client.prepareGet("http://null.apache.org:9999/").execute(new AsyncCompletionHandlerAdapter() {
                @Override
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
                fail("Timed out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncConnectInvalidFuturePort() throws Exception {
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
    public void asyncContentLenghtGETTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public void onThrowable(Throwable t) {
                    fail("Unexpected exception", t);
                }
            }).get();

            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncResponseBodyTooLarge() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.preparePost(getTargetUrl()).setBody("0123456789").execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public void onThrowable(Throwable t) {
                    fail("Unexpected exception", t);
                }
            }).get();

            assertNotNull(response.getResponseBodyExcerpt(Integer.MAX_VALUE));
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncResponseEmptyBody() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public void onThrowable(Throwable t) {
                    fail("Unexpected exception", t);
                }
            }).get();

            assertEquals(response.getResponseBody(), "");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "asyncAPI" })
    public void asyncAPIContentLenghtGETTest() throws Exception {
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
                fail("Timed out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "asyncAPI" })
    public void asyncAPIHandlerExceptionTest() throws Exception {
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
                fail("Timed out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoGetDelayHandlerTest() throws Exception {
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
                        fail("Must not receive a response");
                    } finally {
                        l.countDown();
                    }
                    return response;
                }

                @Override
                public void onThrowable(Throwable t) {
                    try {
                        if (t instanceof TimeoutException) {
                            assertTrue(true);
                        } else {
                            fail("Unexpected exception", t);
                        }
                    } finally {
                        l.countDown();
                    }
                }
            });

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timed out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoGetQueryStringTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            // Use a l in case the assert fail
            final CountDownLatch l = new CountDownLatch(1);

            AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        assertTrue(response.getHeader("X-pathInfo") != null);
                        assertTrue(response.getHeader("X-queryString") != null);
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            };

            Request req = new RequestBuilder("GET").setUrl(getTargetUrl() + "?foo=bar").build();

            client.executeRequest(req, handler).get();

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timed out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoGetKeepAliveHandlerTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
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

            client.prepareGet(getTargetUrl()).execute(handler).get();
            client.prepareGet(getTargetUrl()).execute(handler);

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timed out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider", "async" })
    public void asyncDoGetMaxRedirectTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(new Builder().setMaximumNumberOfRedirects(0).setFollowRedirects(true).build());
        try {
            // Use a l in case the assert fail
            final CountDownLatch l = new CountDownLatch(1);

            AsyncCompletionHandler<Response> handler = new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    fail("Should not be here");
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

            client.prepareGet("http://google.com").execute(handler);

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timed out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider", "async" })
    public void asyncDoGetNestedTest() throws Exception {
        final AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            // FIXME find a proper website that redirects the same number of times whatever the language
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
                            client.prepareGet("http://www.lemonde.fr").execute(this);
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

            client.prepareGet("http://www.lemonde.fr").execute(handler);

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timed out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider", "async" })
    public void asyncDoGetStreamAndBodyTest() throws Exception {
        final AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.prepareGet("http://www.lemonde.fr").execute().get();
            assertEquals(response.getStatusCode(), 200);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider", "async" })
    public void asyncUrlWithoutPathTest() throws Exception {
        final AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.prepareGet("http://www.lemonde.fr").execute().get();
            assertEquals(response.getStatusCode(), 200);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "default_provider", "async" })
    public void optionsTest() throws Exception {
        final AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.prepareOptions(getTargetUrl()).execute().get();

            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getHeader("Allow"), "GET,HEAD,POST,OPTIONS,TRACE");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void testAwsS3() throws Exception {
        final AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.prepareGet("http://test.s3.amazonaws.com/").execute().get();
            if (response.getResponseBody() == null || response.getResponseBody().equals("")) {
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

        final AsyncHttpClient client = getAsyncHttpClient(new Builder().setAsyncHttpClientProviderConfig(getProviderConfig()).build());
        try {
            Response response = client.prepareGet("http://test.s3.amazonaws.com/").execute().get();
            if (response.getResponseBody() == null || response.getResponseBody().equals("")) {
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
                client.prepareGet(getTargetUrl()).setHeaders(h).setUrl(getTargetUrl()).execute().get();
                fail();
            } catch (Throwable ex) {
                final long elapsedTime = millisTime() - t1;
                System.out.println("EXPIRED: " + (elapsedTime));
                assertNotNull(ex.getCause());
                assertTrue(elapsedTime >= 10000 && elapsedTime <= 25000);
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider", "async" })
    public void asyncDoPostCancelTest() throws Exception {

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

                future.cancel(true);
            } catch (IllegalStateException ise) {
                fail();
            }
            assertNotNull(ex.get());
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void getShouldAllowBody() throws IllegalArgumentException, IOException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            client.prepareGet(getTargetUrl()).setBody("Boo!").execute();
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" }, expectedExceptions = IllegalArgumentException.class)
    public void headShouldNotAllowBody() throws IllegalArgumentException, IOException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            client.prepareHead(getTargetUrl()).setBody("Boo!").execute();
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void invalidUri() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.executeRequest(client.prepareGet(String.format("http:127.0.0.1:%d/foo/test", port1)).build()).get();
            assertEquals(200, response.getStatusCode());
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void asyncHttpClientConfigBeanTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfigBean().setUserAgent("test"));
        try {
            Response response = client.executeRequest(client.prepareGet(getTargetUrl()).build()).get();
            assertEquals(200, response.getStatusCode());
        } finally {
            client.close();
        }
    }

    @Test(groups = { "default_provider", "async" })
    public void bodyAsByteTest() throws Exception {
        final AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.prepareGet(getTargetUrl()).execute().get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getResponseBodyAsBytes(), new byte[] {});
        } finally {
            client.close();
        }
    }

    @Test(groups = { "default_provider", "async" })
    public void mirrorByteTest() throws Exception {
        final AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Response response = client.preparePost(getTargetUrl()).setBody("MIRROR").execute().get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(new String(response.getResponseBodyAsBytes(), "UTF-8"), "MIRROR");
        } finally {
            client.close();
        }
    }

    protected abstract AsyncHttpProviderConfig<?, ?> getProviderConfig();
}
