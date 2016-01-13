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
import static org.asynchttpclient.test.TestUtils.*;
import static org.testng.Assert.*;
import io.netty.handler.codec.http.HttpHeaders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class BasicAuthTest extends AbstractBasicTest {

    protected static final String MY_MESSAGE = "my message";

    private Server server2;
    private Server serverNoAuth;
    private int portNoAuth;

    @BeforeClass(alwaysRun = true)
    @Override
    public void setUpGlobal() throws Exception {
        port1 = findFreePort();
        port2 = findFreePort();
        portNoAuth = findFreePort();

        server = newJettyHttpServer(port1);
        addBasicAuthHandler(server, configureHandler());
        server.start();

        server2 = newJettyHttpServer(port2);
        addBasicAuthHandler(server2, new RedirectHandler());
        server2.start();

        // need noAuth server to verify the preemptive auth mode (see
        // basicAuthTestPreemtiveTest)
        serverNoAuth = newJettyHttpServer(portNoAuth);
        serverNoAuth.setHandler(new SimpleHandler());
        serverNoAuth.start();

        logger.info("Local HTTP server started successfully");
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        super.tearDownGlobal();
        server2.stop();
        serverNoAuth.stop();
    }

    @Override
    protected String getTargetUrl() {
        return "http://localhost:" + port1 + "/";
    }

    @Override
    protected String getTargetUrl2() {
        return "http://localhost:" + port2 + "/uff";
    }

    protected String getTargetUrlNoAuth() {
        return "http://localhost:" + portNoAuth + "/";
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
                response.setContentLength(0);
                response.setHeader("Location", "/bla");

            } else {
                LOGGER.info("got redirected" + request.getRequestURI());
                response.setStatus(200);
                response.addHeader("X-Auth", request.getHeader("Authorization"));
                response.addHeader("X-" + HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getContentLength()));
                byte[] b = "content".getBytes(UTF_8);
                response.setContentLength(b.length);
                response.getOutputStream().write(b);
            }
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }

    private static class SimpleHandler extends AbstractHandler {

        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {

            if (request.getHeader("X-401") != null) {
                response.setStatus(401);
                response.setContentLength(0);

            } else {
                response.addHeader("X-Auth", request.getHeader("Authorization"));
                response.addHeader("X-" + HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(request.getContentLength()));
                response.setStatus(200);

                int size = 10 * 1024;
                if (request.getContentLength() > 0) {
                    size = request.getContentLength();
                }
                byte[] bytes = new byte[size];
                int contentLength = 0;
                if (bytes.length > 0) {
                    int read = request.getInputStream().read(bytes);
                    if (read > 0) {
                        contentLength = read;
                        response.getOutputStream().write(bytes, 0, read);
                    }
                }
                response.setContentLength(contentLength);
            }
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }

    @Test(groups = "standalone")
    public void basicAuthTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet(getTargetUrl())//
                    .setRealm(basicAuthRealm(USER, ADMIN).build())//
                    .execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }

    @Test(groups = "standalone")
    public void redirectAndBasicAuthTest() throws Exception, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true).setMaxRedirects(10))) {
            Future<Response> f = client.prepareGet(getTargetUrl2())//
                    .setRealm(basicAuthRealm(USER, ADMIN).build())//
                    .execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
        }
    }

    @Test(groups = "standalone")
    public void basic401Test() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            BoundRequestBuilder r = client.prepareGet(getTargetUrl())//
                    .setHeader("X-401", "401")//
                    .setRealm(basicAuthRealm(USER, ADMIN).build());

            Future<Integer> f = r.execute(new AsyncHandler<Integer>() {

                private HttpResponseStatus status;

                public void onThrowable(Throwable t) {

                }

                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                    return State.CONTINUE;
                }

                public State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                    this.status = responseStatus;

                    if (status.getStatusCode() != 200) {
                        return State.ABORT;
                    }
                    return State.CONTINUE;
                }

                public State onHeadersReceived(HttpResponseHeaders headers) throws Exception {
                    return State.CONTINUE;
                }

                public Integer onCompleted() throws Exception {
                    return status.getStatusCode();
                }
            });
            Integer statusCode = f.get(10, TimeUnit.SECONDS);
            assertNotNull(statusCode);
            assertEquals(statusCode.intValue(), 401);
        }
    }

    @Test(groups = "standalone")
    public void basicAuthTestPreemtiveTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            // send the request to the no-auth endpoint to be able to verify the
            // auth header is really sent preemptively for the initial call.
            Future<Response> f = client.prepareGet(getTargetUrlNoAuth())//
                    .setRealm(basicAuthRealm(USER, ADMIN).setUsePreemptiveAuth(true).build())//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }

    @Test(groups = "standalone")
    public void basicAuthNegativeTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet(getTargetUrl())//
                    .setRealm(basicAuthRealm("fake", ADMIN).build())//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), 401);
        }
    }

    @Test(groups = "standalone")
    public void basicAuthInputStreamTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.preparePost(getTargetUrl())//
                    .setBody(new ByteArrayInputStream("test".getBytes()))//
                    .setRealm(basicAuthRealm(USER, ADMIN).build())//
                    .execute();

            Response resp = f.get(30, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), "test");
        }
    }

    @Test(groups = "standalone")
    public void basicAuthFileTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.preparePost(getTargetUrl())//
                    .setBody(SIMPLE_TEXT_FILE)//
                    .setRealm(basicAuthRealm(USER, ADMIN).build())//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        }
    }

    @Test(groups = "standalone")
    public void basicAuthAsyncConfigTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRealm(basicAuthRealm(USER, ADMIN)))) {
            Future<Response> f = client.preparePost(getTargetUrl())//
                    .setBody(SIMPLE_TEXT_FILE_STRING)//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        }
    }

    @Test(groups = "standalone")
    public void basicAuthFileNoKeepAliveTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(false))) {

            Future<Response> f = client.preparePost(getTargetUrl())//
                    .setBody(SIMPLE_TEXT_FILE)//
                    .setRealm(basicAuthRealm(USER, ADMIN).build())//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        }
    }

    @Test(groups = "standalone")
    public void noneAuthTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            BoundRequestBuilder r = client.prepareGet(getTargetUrl()).setRealm(basicAuthRealm(USER, ADMIN).build());

            Future<Response> f = r.execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }
}
