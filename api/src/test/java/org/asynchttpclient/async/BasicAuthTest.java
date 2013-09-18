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
import static org.testng.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Response;
import org.asynchttpclient.SimpleAsyncHttpClient;
import org.asynchttpclient.consumers.AppendableBodyConsumer;
import org.asynchttpclient.generators.InputStreamBodyGenerator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public abstract class BasicAuthTest extends AbstractBasicTest {

    protected static final String MY_MESSAGE = "my message";

    private Server server2;

    public abstract String getProviderClass();

    @BeforeClass(alwaysRun = true)
    @Override
    public void setUpGlobal() throws Exception {
        port1 = findFreePort();
        port2 = findFreePort();

        server = newJettyHttpServer(port1);
        addBasicAuthHandler(server, false, configureHandler());
        server.start();

        server2 = newJettyHttpServer(port2);
        addDigestAuthHandler(server2, true, new RedirectHandler());
        server2.start();

        logger.info("Local HTTP server started successfully");
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        super.tearDownGlobal();
        server2.stop();
    }

    @Override
    protected String getTargetUrl() {
        return "http://127.0.0.1:" + port1 + "/";
    }

    @Override
    protected String getTargetUrl2() {
        return "http://127.0.0.1:" + port2 + "/uff";
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new SimpleHandler();
    }

    private static class RedirectHandler extends AbstractHandler {

        private static final Logger LOGGER = LoggerFactory.getLogger(RedirectHandler.class);

        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {

            LOGGER.info("request: " + request.getRequestURI());
            if ("/uff".equals(request.getRequestURI())) {

                LOGGER.info("redirect to /bla");
                response.setStatus(302);
                response.setHeader("Location", "/bla");
                response.getOutputStream().flush();
                response.getOutputStream().close();

                return;

            } else {
                LOGGER.info("got redirected" + request.getRequestURI());
                response.addHeader("X-Auth", request.getHeader("Authorization"));
                response.addHeader("X-Content-Length", String.valueOf(request.getContentLength()));
                response.setStatus(200);
                response.getOutputStream().write("content".getBytes("UTF-8"));
                response.getOutputStream().flush();
                response.getOutputStream().close();
            }
        }
    }

    private static class SimpleHandler extends AbstractHandler {

        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {

            if (request.getHeader("X-401") != null) {
                response.setStatus(401);
                response.getOutputStream().flush();
                response.getOutputStream().close();

                return;
            }
            response.addHeader("X-Auth", request.getHeader("Authorization"));
            response.addHeader("X-Content-Length", String.valueOf(request.getContentLength()));
            response.setStatus(200);

            int size = 10 * 1024;
            if (request.getContentLength() > 0) {
                size = request.getContentLength();
            }
            byte[] bytes = new byte[size];
            if (bytes.length > 0) {
                int read = request.getInputStream().read(bytes);
                if (read > 0) {
                    response.getOutputStream().write(bytes, 0, read);
                }
            }
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Future<Response> f = client.prepareGet(getTargetUrl())//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build())//
                    .execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void redirectAndBasicAuthTest() throws Exception, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).setMaximumNumberOfRedirects(10).build());
        try {
            Future<Response> f = client.prepareGet(getTargetUrl2())//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build())//
                    .execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));

        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basic401Test() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(getTargetUrl())//
                    .setHeader("X-401", "401")//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build());

            Future<Integer> f = r.execute(new AsyncHandler<Integer>() {

                private HttpResponseStatus status;

                public void onThrowable(Throwable t) {

                }

                public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                    return STATE.CONTINUE;
                }

                public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                    this.status = responseStatus;

                    if (status.getStatusCode() != 200) {
                        return STATE.ABORT;
                    }
                    return STATE.CONTINUE;
                }

                public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
                    return STATE.CONTINUE;
                }

                public Integer onCompleted() throws Exception {
                    return status.getStatusCode();
                }
            });
            Integer statusCode = f.get(10, TimeUnit.SECONDS);
            assertNotNull(statusCode);
            assertEquals(statusCode.intValue(), 401);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthTestPreemtiveTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Future<Response> f = client.prepareGet(getTargetUrl())//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).setUsePreemptiveAuth(true).build())//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthNegativeTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Future<Response> f = client.prepareGet(getTargetUrl())//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal("fake").setPassword(ADMIN).build())//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), 401);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthInputStreamTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Future<Response> f = client.preparePost(getTargetUrl())//
                    .setBody(new ByteArrayInputStream("test".getBytes()))//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build())//
                    .execute();

            Response resp = f.get(30, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), "test");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthFileTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Future<Response> f = client.preparePost(getTargetUrl())//
                    .setBody(SIMPLE_TEXT_FILE)//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build())//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthAsyncConfigTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build()).build());
        try {
            Future<Response> f = client.preparePost(getTargetUrl())//
                    .setBody(SIMPLE_TEXT_FILE_STRING)//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthFileNoKeepAliveTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setAllowPoolingConnection(false).build());
        try {

            Future<Response> f = client.preparePost(getTargetUrl())//
                    .setBody(SIMPLE_TEXT_FILE)//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build())//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void stringBuilderBodyConsumerTest() throws Exception {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setRealmPrincipal(USER).setRealmPassword(ADMIN)
                .setUrl(getTargetUrl()).setHeader("Content-Type", "text/html").build();
        try {
            StringBuilder s = new StringBuilder();
            Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new AppendableBodyConsumer(s));

            Response response = future.get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(s.toString(), MY_MESSAGE);
            assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(response.getHeader("X-Auth"));
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void noneAuthTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(getTargetUrl()).setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build());

            Future<Response> f = r.execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        } finally {
            client.close();
        }
    }
}
