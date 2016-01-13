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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.EventCollectingHandler.*;
import static org.asynchttpclient.test.TestUtils.*;
import static org.asynchttpclient.util.DateUtils.millisTime;
import static org.testng.Assert.*;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
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

import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.handler.MaxRedirectException;
import org.asynchttpclient.request.body.multipart.Part;
import org.asynchttpclient.request.body.multipart.StringPart;
import org.asynchttpclient.test.EventCollectingHandler;
import org.testng.annotations.Test;

public class BasicHttpTest extends AbstractBasicTest {

    @Test(groups = "standalone")
    public void asyncProviderEncodingTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Request request = get(getTargetUrl() + "?q=+%20x").build();
            assertEquals(request.getUrl(), getTargetUrl() + "?q=+%20x");

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
            assertEquals(url, getTargetUrl() + "?q=+%20x");
        }
    }

    @Test(groups = "standalone")
    public void asyncProviderEncodingTest2() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Request request = get(getTargetUrl() + "").addQueryParam("q", "a b").build();

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
        }
    }

    @Test(groups = "standalone")
    public void emptyRequestURI() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Request request = get(getTargetUrl()).build();

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
        }
    }

    @Test(groups = "standalone")
    public void asyncProviderContentLenghtGETTest() throws Exception {
        final HttpURLConnection connection = (HttpURLConnection) new URL(getTargetUrl()).openConnection();
        connection.connect();
        final int ct = connection.getContentLength();
        connection.disconnect();
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);

            Request request = get(getTargetUrl()).build();
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
        }
    }

    @Test(groups = "standalone")
    public void asyncContentTypeGETTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = get(getTargetUrl()).build();
            client.executeRequest(request, new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getStatusCode(), 200);
                        assertContentTypesEquals(response.getContentType(), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            }).get();
            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        }
    }

    @Test(groups = "standalone")
    public void asyncHeaderGETTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = get(getTargetUrl()).build();
            client.executeRequest(request, new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getStatusCode(), 200);
                        assertContentTypesEquals(response.getContentType(), TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            }).get();

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        }
    }

    @Test(groups = "standalone")
    public void asyncHeaderPOSTTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            HttpHeaders h = new DefaultHttpHeaders();
            h.add("Test1", "Test1");
            h.add("Test2", "Test2");
            h.add("Test3", "Test3");
            h.add("Test4", "Test4");
            h.add("Test5", "Test5");
            Request request = get(getTargetUrl()).setHeaders(h).build();

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
        }
    }

    @Test(groups = "standalone")
    public void asyncParamPOSTTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);

            Map<String, List<String>> m = new HashMap<>();
            for (int i = 0; i < 5; i++) {
                m.put("param_" + i, Arrays.asList("value_" + i));
            }
            Request request = post(getTargetUrl()).setHeaders(h).setFormParams(m).build();
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
        }
    }

    @Test(groups = "standalone")
    public void asyncStatusHEADTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = head(getTargetUrl()).build();
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
        }
    }

    // TODO: fix test
    @Test(groups = "standalone", enabled = false)
    public void asyncStatusHEADContentLenghtTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(120 * 1000))) {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = head(getTargetUrl()).build();

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
        }
    }

    @Test(groups = "online", expectedExceptions = NullPointerException.class)
    public void asyncNullSchemeTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            client.prepareGet("www.sun.com").execute();
        }
    }

    @Test(groups = "standalone")
    public void asyncDoGetTransferEncodingTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
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
        }
    }

    @Test(groups = "standalone")
    public void asyncDoGetHeadersTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            HttpHeaders h = new DefaultHttpHeaders();
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
        }
    }

    @Test(groups = "standalone")
    public void asyncDoGetCookieTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            HttpHeaders h = new DefaultHttpHeaders();
            h.add("Test1", "Test1");
            h.add("Test2", "Test2");
            h.add("Test3", "Test3");
            h.add("Test4", "Test4");
            h.add("Test5", "Test5");

            final Cookie coo = Cookie.newValidCookie("foo", "value", false, "/", "/", Long.MIN_VALUE, false, false);
            client.prepareGet(getTargetUrl()).setHeaders(h).addCookie(coo).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getStatusCode(), 200);
                        List<Cookie> cookies = response.getCookies();
                        assertEquals(cookies.size(), 1);
                        assertEquals(cookies.get(0).toString(), "foo=value");
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            }).get();

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPostDefaultContentType() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            client.preparePost(getTargetUrl()).addFormParam("foo", "bar").execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getStatusCode(), 200);
                        HttpHeaders h = response.getHeaders();
                        assertEquals(h.get("X-Content-Type"), HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            }).get();

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPostBodyIsoTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.preparePost(getTargetUrl()).addHeader("X-ISO", "true").setBody("\u017D\u017D\u017D\u017D\u017D\u017D").execute().get();
            assertEquals(response.getResponseBody().getBytes("ISO-8859-1"), "\u017D\u017D\u017D\u017D\u017D\u017D".getBytes("ISO-8859-1"));
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPostBytesTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
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
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPostInputStreamTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
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
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPutInputStreamTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
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
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPostMultiPartTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);

            Part p = new StringPart("foo", "bar");

            client.preparePost(getTargetUrl()).addBodyPart(p).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        String xContentType = response.getHeader("X-Content-Type");
                        String boundary = xContentType.substring((xContentType.indexOf("boundary") + "boundary".length() + 1));

                        assertTrue(response.getResponseBody().regionMatches(false, "--".length(), boundary, 0, boundary.length()));
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            }).get();
            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPostBasicGZIPTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setCompressionEnforced(true))) {
            final CountDownLatch l = new CountDownLatch(1);
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
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
                        assertEquals(response.getHeader("X-Accept-Encoding"), "gzip,deflate");
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            }).get();

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPostProxyTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setProxyServer(proxyServer("localhost", port2).build()))) {
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
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
            assertEquals(response.getHeader("X-" + HttpHeaders.Names.CONTENT_TYPE), HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
        }
    }

    @Test(groups = "standalone")
    public void asyncRequestVirtualServerPOSTTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);

            Map<String, List<String>> m = new HashMap<>();
            for (int i = 0; i < 5; i++) {
                m.put("param_" + i, Arrays.asList("value_" + i));
            }
            Request request = post(getTargetUrl()).setHeaders(h).setFormParams(m).setVirtualHost("localhost:" + port1).build();

            Response response = client.executeRequest(request, new AsyncCompletionHandlerAdapter()).get();

            assertEquals(response.getStatusCode(), 200);
            if (response.getHeader("X-Host").startsWith("localhost")) {
                assertEquals(response.getHeader("X-Host"), "localhost:" + port1);
            } else {
                assertEquals(response.getHeader("X-Host"), "localhost:" + port1);
            }
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPutTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_").append(i).append("=value_").append(i).append("&");
            }
            sb.setLength(sb.length() - 1);

            Response response = client.preparePut(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter()).get();

            assertEquals(response.getStatusCode(), 200);
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPostLatchBytesTest() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
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
        }
    }

    @Test(groups = "standalone", expectedExceptions = CancellationException.class)
    public void asyncDoPostDelayCancelTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
            h.add("LockThread", "true");
            StringBuilder sb = new StringBuilder();
            sb.append("LockThread=true");

            Future<Response> future = client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {
                @Override
                public void onThrowable(Throwable t) {
                }
            });
            future.cancel(true);
            future.get(TIMEOUT, TimeUnit.SECONDS);
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPostDelayBytesTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
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
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPostNullBytesTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_").append(i).append("=value_").append(i).append("&");
            }
            sb.setLength(sb.length() - 1);

            Future<Response> future = client.preparePost(getTargetUrl()).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter());

            Response response = future.get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPostListenerBytesTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
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
        }
    }

    @Test(groups = "standalone")
    public void asyncConnectInvalidFuture() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            int dummyPort = findFreePort();
            final AtomicInteger count = new AtomicInteger();
            for (int i = 0; i < 20; i++) {
                try {
                    Response response = client.preparePost(String.format("http://localhost:%d/", dummyPort)).execute(new AsyncCompletionHandlerAdapter() {
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
        }
    }

    @Test(groups = "standalone")
    public void asyncConnectInvalidPortFuture() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            int dummyPort = findFreePort();
            try {
                Response response = client.preparePost(String.format("http://localhost:%d/", dummyPort)).execute(new AsyncCompletionHandlerAdapter() {
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
        }
    }

    @Test(groups = "standalone")
    public void asyncConnectInvalidPort() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            // pick a random unused local port
            int port = findFreePort();

            try {
                Response response = client.preparePost(String.format("http://localhost:%d/", port)).execute(new AsyncCompletionHandlerAdapter() {
                    @Override
                    public void onThrowable(Throwable t) {
                        t.printStackTrace();
                    }
                }).get();
                assertNull(response, "No ExecutionException was thrown");
            } catch (ExecutionException ex) {
                assertEquals(ex.getCause().getClass(), ConnectException.class);
            }
        }
    }

    @Test(groups = "standalone")
    public void asyncConnectInvalidHandlerPort() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch l = new CountDownLatch(1);
            int port = findFreePort();

            client.prepareGet(String.format("http://localhost:%d/", port)).execute(new AsyncCompletionHandlerAdapter() {
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
        }
    }

    @Test(groups = "online", expectedExceptions = UnknownHostException.class)
    public void asyncConnectInvalidHandlerHost() throws Throwable {
        try (AsyncHttpClient client = asyncHttpClient()) {

            final AtomicReference<Throwable> e = new AtomicReference<>();
            final CountDownLatch l = new CountDownLatch(1);

            client.prepareGet("http://null.apache.org:9999/").execute(new AsyncCompletionHandlerAdapter() {
                @Override
                public void onThrowable(Throwable t) {
                    e.set(t);
                    l.countDown();
                }
            });

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timed out");
            }

            assertNotNull(e.get());
            throw e.get();
        }
    }

    @Test(groups = "standalone")
    public void asyncConnectInvalidFuturePort() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final AtomicBoolean called = new AtomicBoolean(false);
            final AtomicBoolean rightCause = new AtomicBoolean(false);
            // pick a random unused local port
            int port = findFreePort();

            try {
                Response response = client.prepareGet(String.format("http://localhost:%d/", port)).execute(new AsyncCompletionHandlerAdapter() {
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
        }
    }

    @Test(groups = "standalone")
    public void asyncContentLenghtGETTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public void onThrowable(Throwable t) {
                    fail("Unexpected exception", t);
                }
            }).get();

            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
        }
    }

    @Test(groups = "standalone")
    public void asyncResponseEmptyBody() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.prepareGet(getTargetUrl()).execute(new AsyncCompletionHandlerAdapter() {

                @Override
                public void onThrowable(Throwable t) {
                    fail("Unexpected exception", t);
                }
            }).get();

            assertEquals(response.getResponseBody(), "");
        }
    }

    @Test(groups = "standalone")
    public void asyncAPIContentLenghtGETTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
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
        }
    }

    @Test(groups = "standalone")
    public void asyncAPIHandlerExceptionTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
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
        }
    }

    @Test(groups = "standalone")
    public void asyncDoGetDelayHandlerTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(5 * 1000))) {
            HttpHeaders h = new DefaultHttpHeaders();
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
        }
    }

    @Test(groups = "standalone")
    public void asyncDoGetQueryStringTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
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

            Request req = get(getTargetUrl() + "?foo=bar").build();

            client.executeRequest(req, handler).get();

            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timed out");
            }
        }
    }

    @Test(groups = "standalone")
    public void asyncDoGetKeepAliveHandlerTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
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
        }
    }

    @Test(groups = "online")
    public void asyncDoGetMaxRedirectTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setMaxRedirects(0).setFollowRedirect(true))) {
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
        }
    }

    @Test(groups = "online")
    public void asyncDoGetNestedTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            // FIXME find a proper website that redirects the same number of
            // times whatever the language
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
        }
    }

    @Test(groups = "online")
    public void asyncDoGetStreamAndBodyTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.prepareGet("http://www.lemonde.fr").execute().get();
            assertEquals(response.getStatusCode(), 200);
        }
    }

    @Test(groups = "online")
    public void asyncUrlWithoutPathTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.prepareGet("http://www.lemonde.fr").execute().get();
            assertEquals(response.getStatusCode(), 200);
        }
    }

    @Test(groups = "standalone")
    public void optionsTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.prepareOptions(getTargetUrl()).execute().get();

            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getHeader("Allow"), "GET,HEAD,POST,OPTIONS,TRACE");
        }
    }

    @Test(groups = "online")
    public void testAwsS3() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.prepareGet("http://test.s3.amazonaws.com/").execute().get();
            if (response.getResponseBody() == null || response.getResponseBody().equals("")) {
                fail("No response Body");
            } else {
                assertEquals(response.getStatusCode(), 403);
            }
        }
    }

    @Test(groups = "online")
    public void testAsyncHttpProviderConfig() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().addChannelOption(ChannelOption.TCP_NODELAY, Boolean.TRUE))) {
            Response response = client.prepareGet("http://test.s3.amazonaws.com/").execute().get();
            if (response.getResponseBody() == null || response.getResponseBody().equals("")) {
                fail("No response Body");
            } else {
                assertEquals(response.getStatusCode(), 403);
            }
        }
    }

    @Test(groups = "standalone")
    public void idleRequestTimeoutTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setPooledConnectionIdleTimeout(5000).setRequestTimeout(10000))) {
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
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
        }
    }

    @Test(groups = "standalone")
    public void asyncDoPostCancelTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
            h.add("LockThread", "true");
            StringBuilder sb = new StringBuilder();
            sb.append("LockThread=true");

            final AtomicReference<CancellationException> ex = new AtomicReference<>();
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
        }
    }

    @Test(groups = "standalone")
    public void getShouldAllowBody() throws IOException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            client.prepareGet(getTargetUrl()).setBody("Boo!").execute();
        }
    }

    @Test(groups = "standalone", expectedExceptions = NullPointerException.class)
    public void invalidUri() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            client.prepareGet(String.format("http:localhost:%d/foo/test", port1)).build();
        }
    }

    @Test(groups = "standalone")
    public void bodyAsByteTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.prepareGet(getTargetUrl()).execute().get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getResponseBodyAsBytes(), new byte[] {});
        }
    }

    @Test(groups = "standalone")
    public void mirrorByteTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.preparePost(getTargetUrl()).setBody("MIRROR").execute().get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(new String(response.getResponseBodyAsBytes(), UTF_8), "MIRROR");
        }
    }

    @Test(groups = "standalone")
    public void testNewConnectionEventsFired() throws Exception {
        Request request = get("http://localhost:" + port1 + "/Test").build();

        try (AsyncHttpClient client = asyncHttpClient()) {
            EventCollectingHandler handler = new EventCollectingHandler();
            client.executeRequest(request, handler).get(3, TimeUnit.SECONDS);
            handler.waitForCompletion(3, TimeUnit.SECONDS);

            Object[] expectedEvents = new Object[] {//
            CONNECTION_POOL_EVENT,//
                    HOSTNAME_RESOLUTION_EVENT,//
                    HOSTNAME_RESOLUTION_SUCCESS_EVENT,//
                    CONNECTION_OPEN_EVENT,//
                    CONNECTION_SUCCESS_EVENT,//
                    REQUEST_SEND_EVENT,//
                    HEADERS_WRITTEN_EVENT,//
                    STATUS_RECEIVED_EVENT,//
                    HEADERS_RECEIVED_EVENT,//
                    CONNECTION_OFFER_EVENT,//
                    COMPLETED_EVENT };

            assertEquals(handler.firedEvents.toArray(), expectedEvents, "Got " + Arrays.toString(handler.firedEvents.toArray()));
        }
    }
}
