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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
                Assert.assertEquals(response.getStatusCode(), 200);
                int contentLenght = -1;
                if (response.getHeader("content-length") != null) {
                    contentLenght = Integer.valueOf(response.getHeader("content-length"));
                }
                int ct = connection.getContentLength();
                Assert.assertEquals(contentLenght, ct);
                l.countDown();
                return response;
            }

            @Override
            public void onThrowable(Throwable t) {
                Assert.fail("Unexpected exception", t);
                l.countDown();
            }


        }).get();

        if (!l.await(5, TimeUnit.SECONDS)) {
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
                Assert.assertEquals(response.getStatusCode(), 200);
                Assert.assertEquals(response.getContentType(), "text/html; charset=utf-8");
                l.countDown();
                return response;
            }
        }).get();

        if (!l.await(5, TimeUnit.SECONDS)) {
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
                Assert.assertEquals(response.getStatusCode(), 200);
                Assert.assertEquals(response.getContentType(), "text/html; charset=utf-8");

                l.countDown();
                return response;
            }
        }).get();

        if (!l.await(5, TimeUnit.SECONDS)) {
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
                System.out.println(">>>>> " + response.getStatusText());
                Assert.assertEquals(response.getStatusCode(), 200);
                for (int i = 1; i < 5; i++) {
                    Assert.assertEquals(response.getHeader("X-Test" + i), "Test" + i);
                }
                l.countDown();
                l.countDown();
                return response;
            }
        }).get();

        if (!l.await(5, TimeUnit.SECONDS)) {
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

                Assert.assertEquals(response.getStatusCode(), 200);
                for (int i = 1; i < 5; i++) {
                    System.out.println(">>>>> " + response.getHeader("X-param_" + i));
                    Assert.assertEquals(response.getHeader("X-param_" + i), "value_" + i);
                }

                l.countDown();
                return response;
            }
        }).get();

        if (!l.await(5, TimeUnit.SECONDS)) {
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
                Assert.assertEquals(response.getStatusCode(), 200);
                l.countDown();
                return response;
            }
        }).get();

        if (!l.await(5, TimeUnit.SECONDS)) {
            Assert.fail("Timeout out");
        }

    }

    @Test(groups = "async")
    public void asyncNullSchemeTest() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();

        try{
            c.prepareGet("www.sun.com").execute();
            Assert.fail();
        } catch (IllegalArgumentException ex){
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

                Assert.assertEquals(response.getStatusCode(), 200);
                Assert.assertEquals(response.getHeader("Transfer-Encoding"), "chunked");
                l.countDown();
                return response;
            }
        }).get();

        if (!l.await(5, TimeUnit.SECONDS)) {
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

                Assert.assertEquals(response.getStatusCode(), 200);
                for (int i = 1; i < 5; i++) {
                    Assert.assertEquals(response.getHeader("X-Test" + i), "Test" + i);
                }
                l.countDown();
                return response;
            }
        }).get();
        if (!l.await(5, TimeUnit.SECONDS)) {
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

        Cookie coo = new Cookie("/", "foo", "value", "/", 3000, false);
        c.prepareGet(TARGET_URL).setHeaders(h).addCookie(coo).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                System.out.println(">>>> " + response.getHeader("Set-Cookie"));
                Assert.assertEquals(response.getStatusCode(), 200);
                Assert.assertEquals("foo=value;Path=/;Domain=/", response.getHeader("Set-Cookie"));
                l.countDown();
                return response;
            }
        }).get();
        if (!l.await(5, TimeUnit.SECONDS)) {
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
                Assert.assertEquals(response.getStatusCode(), 200);
                for (int i = 1; i < 5; i++) {
                    System.out.println(">>>>> " + response.getHeader("X-param_" + i));
                    Assert.assertEquals(response.getHeader("X-param_" + i), "value_" + i);

                }
                l.countDown();
                return response;
            }
        }).get();
        if (!l.await(5, TimeUnit.SECONDS)) {
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
                Assert.assertEquals(response.getStatusCode(), 200);
                for (int i = 1; i < 5; i++) {
                    System.out.println(">>>>> " + response.getHeader("X-param_" + i));
                    Assert.assertEquals(response.getHeader("X-param_" + i), "value_" + i);

                }
                l.countDown();
                return response;
            }
        }).get();
        if (!l.await(5, TimeUnit.SECONDS)) {
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
                Assert.assertEquals(response.getStatusCode(), 200);
                for (int i = 1; i < 5; i++) {
                    System.out.println(">>>>> " + response.getHeader("X-param_" + i));
                    Assert.assertEquals(response.getHeader("X-param_" + i), "value_" + i);
                }
                l.countDown();
                return response;
            }
        }).get();
        if (!l.await(5, TimeUnit.SECONDS)) {
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
                String xContentType = response.getHeader("X-Content-Type");
                String boundary = xContentType.substring(
                        (xContentType.indexOf("boundary") + "boundary".length() + 1));

                String s = response.getResponseBodyExcerpt(boundary.length() + "--".length()).substring("--".length());
                Assert.assertEquals(boundary, s);
                l.countDown();
                return response;
            }
        }).get();
        if (!l.await(5, TimeUnit.SECONDS)) {
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
                Assert.assertEquals(response.getStatusCode(), 200);
                Assert.assertEquals(response.getHeader("X-Accept-Encoding"), "gzip");
                l.countDown();
                return response;
            }
        }).get();
        if (!l.await(5, TimeUnit.SECONDS)) {
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



        Assert.assertEquals(response.getStatusCode(), 200);
        Assert.assertEquals(response.getHeader("X-Proxy-Connection"), "keep-alive");
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

        Assert.assertEquals(response.getStatusCode(), 200);
        Assert.assertEquals(response.getHeader("X-Host"), "localhost:19999");

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

        Assert.assertEquals(response.getStatusCode(), 200);
        Assert.assertEquals(response.getHeader("X-param_1"), null);

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

        /*
         * Use a Latch to simulate asynchronous response.
         */
        final CountDownLatch latch = new CountDownLatch(1);

        c.preparePost(TARGET_URL).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    Assert.assertEquals(response.getStatusCode(), 200);
                    for (int i = 1; i < 5; i++) {
                        System.out.println(">>>>> " + response.getHeader("X-param_" + i));
                        Assert.assertEquals(response.getHeader("X-param_" + i), "value_" + i);

                    }
                    l.countDown();
                    return response;
                } finally {
                    latch.countDown();
                }
            }
        });

        if (!l.await(5, TimeUnit.SECONDS)) {
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
        Response response = future.get(5, TimeUnit.SECONDS);
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
            Future<Response> future = c.preparePost(TARGET_URL).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter(){
                @Override
                public void onThrowable(Throwable t) {
                    t.printStackTrace();
                }
            });

            future.get(5, TimeUnit.SECONDS);
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
        Assert.assertEquals(response.getStatusCode(), 200);

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

        final CountDownLatch latch = new CountDownLatch(1);

        c.preparePost(TARGET_URL).setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter() {

                    @Override
                    public Response onCompleted(Response response) throws Exception {
                        Assert.assertEquals(response.getStatusCode(), 200);
                        latch.countDown();
                        return response;
                    }
                });

        if (!latch.await(10, TimeUnit.SECONDS)) {
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

        IOException expected = null;
        try {
            c.preparePost("http://127.0.0.1:9999/").setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter());
        } catch (IOException ex) {
            expected = ex;
        }

        if (expected != null) {
            Assert.assertTrue(true);
        } else {
            Assert.fail("Must have thrown an IOException");
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

        try{
            c.preparePost("http://127.0.0.1:9999/").setHeaders(h).setBody(sb.toString()).execute(new AsyncCompletionHandlerAdapter());
            Assert.assertTrue(false);
        } catch (ConnectException ex){
            Assert.assertTrue(true);
        }
    }

    @Test(groups = "async")
    public void asyncConnectInvalidFuturePort() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();

        try {
            c.prepareGet("http://127.0.0.1:9999/").execute(new AsyncCompletionHandlerAdapter());
            Assert.fail("No ConnectionException was thrown");
        } catch (ConnectException ex) {
            Assert.assertEquals(ex.getMessage(), "Connection refused: http://127.0.0.1:9999/");
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
        Assert.assertEquals(response.getStatusCode(), 200);
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

        // Use a latch in case the assert fail
        final CountDownLatch latch = new CountDownLatch(1);

        client.prepareGet(TARGET_URL).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                Assert.assertEquals(response.getStatusCode(), 200);
                latch.countDown();
                return response;
            }

            @Override
            public void onThrowable(Throwable t) {
            }
        });


        if (!latch.await(10, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
    }

    @Test(groups = "asyncAPI")
    public void asyncAPIHandlerExceptionTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient();

        // Use a latch in case the assert fail
        final CountDownLatch latch = new CountDownLatch(1);

        client.prepareGet(TARGET_URL).execute(new AsyncCompletionHandlerAdapter() {
            @Override
            public Response onCompleted(Response response) throws Exception {
                throw new IllegalStateException("FOO");
            }

            @Override
            public void onThrowable(Throwable t) {
                t.printStackTrace();
                if (t.getMessage() != null) {
                    Assert.assertEquals(t.getMessage(), "FOO");
                    latch.countDown();
                }
            }
        });


        if (!latch.await(10, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
    }

    @Test(groups = "async")
    public void asyncDoGetDelayHandlerTest() throws Throwable {
        Headers h = new Headers();
        h.add("LockThread", "true");
        AsyncHttpClient client = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setRequestTimeout(5 * 1000).build());

        // Use a latch in case the assert fail
        final CountDownLatch latch = new CountDownLatch(1);

        client.prepareGet(TARGET_URL).setHeaders(h).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                    Assert.fail("Must not receive a response");
                } finally {
                    latch.countDown();
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
                    latch.countDown();
                }
            }
        });

        if (!latch.await(10, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
    }

    @Test(groups = "async")
    public void asyncDoGetQueryStringTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient();

        // Use a latch in case the assert fail
        final CountDownLatch latch = new CountDownLatch(1);

        AsyncCompletionHandler handler = new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                Assert.assertTrue(response.getHeader("X-pathInfo") != null);
                Assert.assertTrue(response.getHeader("X-queryString") != null);
                latch.countDown();
                return response;
            }
        };

        Request req = new RequestBuilder(RequestType.GET)
                .setUrl(TARGET_URL + "?foo=bar").build();

        client.executeRequest(req,handler).get();

        if (!latch.await(10, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
    }

    @Test(groups = "async")
    public void asyncDoGetKeepAliveHandlerTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient();

        // Use a latch in case the assert fail
        final CountDownLatch latch = new CountDownLatch(2);

        AsyncCompletionHandler handler = new AsyncCompletionHandlerAdapter() {

            String remoteAddr = null;

            @Override
            public Response onCompleted(Response response) throws Exception {

                Assert.assertEquals(response.getStatusCode(),200);
                if (remoteAddr == null){
                    remoteAddr = response.getHeader("X-KEEP-ALIVE");
                    latch.countDown();
                } else {
                    Assert.assertEquals(response.getHeader("X-KEEP-ALIVE"),remoteAddr);
                    latch.countDown();
                }
                return response;
            }
        };

        client.prepareGet(TARGET_URL).execute(handler).get();
        client.prepareGet(TARGET_URL).execute(handler);

        if (!latch.await(10, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
    }

    @Test(groups = "async")
    public void asyncDoGetMaxConnectionsTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient(new Builder().setMaximumConnectionsTotal(2).build());

        // Use a latch in case the assert fail
        final CountDownLatch latch = new CountDownLatch(2);

        AsyncCompletionHandler handler = new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                latch.countDown();
                return response;
            }

            @Override
            public void onThrowable(Throwable t) {
                try {
                    Assert.fail("Unexpected exception", t);
                } finally {
                    latch.countDown();
                }
            }
        };

        client.prepareGet("http://www.oracle.com/index.html").execute(handler).get();
        client.prepareGet("http://www.apache.org/").execute(handler).get();

        try{
            client.prepareGet("http://www.ning.com/").execute(handler).get();
            Assert.fail();
        } catch (IOException ex){
            String s = ex.getMessage();
            Assert.assertEquals(s,"Too many connections");
        }

        if (!latch.await(10, TimeUnit.SECONDS)) {
            Assert.fail("Timed out");
        }
        client.close();
    }

}
