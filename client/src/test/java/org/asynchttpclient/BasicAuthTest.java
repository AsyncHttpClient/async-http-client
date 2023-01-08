/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
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

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.handler.codec.http.HttpHeaders;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.ADMIN;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE_STRING;
import static org.asynchttpclient.test.TestUtils.USER;
import static org.asynchttpclient.test.TestUtils.addBasicAuthHandler;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class BasicAuthTest extends AbstractBasicTest {

    private Server server2;
    private Server serverNoAuth;
    private int portNoAuth;

    @Override
    @BeforeEach
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector1 = addHttpConnector(server);
        addBasicAuthHandler(server, configureHandler());
        server.start();
        port1 = connector1.getLocalPort();

        server2 = new Server();
        ServerConnector connector2 = addHttpConnector(server2);
        addBasicAuthHandler(server2, new RedirectHandler());
        server2.start();
        port2 = connector2.getLocalPort();

        // need noAuth server to verify the preemptive auth mode (see basicAuthTestPreemptiveTest)
        serverNoAuth = new Server();
        ServerConnector connectorNoAuth = addHttpConnector(serverNoAuth);
        serverNoAuth.setHandler(new SimpleHandler());
        serverNoAuth.start();
        portNoAuth = connectorNoAuth.getLocalPort();

        logger.info("Local HTTP server started successfully");
    }

    @Override
    @AfterEach
    public void tearDownGlobal() throws Exception {
        super.tearDownGlobal();
        server2.stop();
        serverNoAuth.stop();
    }

    @Override
    protected String getTargetUrl() {
        return "http://localhost:" + port1 + '/';
    }

    @Override
    protected String getTargetUrl2() {
        return "http://localhost:" + port2 + "/uff";
    }

    private String getTargetUrlNoAuth() {
        return "http://localhost:" + portNoAuth + '/';
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new SimpleHandler();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void basicAuthTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet(getTargetUrl())
                    .setRealm(basicAuthRealm(USER, ADMIN).build())
                    .execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void redirectAndBasicAuthTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true).setMaxRedirects(10))) {
            Future<Response> f = client.prepareGet(getTargetUrl2())
                    .setRealm(basicAuthRealm(USER, ADMIN).build())
                    .execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void basic401Test() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            BoundRequestBuilder r = client.prepareGet(getTargetUrl())
                    .setHeader("X-401", "401")
                    .setRealm(basicAuthRealm(USER, ADMIN).build());

            Future<Integer> f = r.execute(new AsyncHandler<Integer>() {

                private HttpResponseStatus status;

                @Override
                public void onThrowable(Throwable t) {

                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
                    return State.CONTINUE;
                }

                @Override
                public State onStatusReceived(HttpResponseStatus responseStatus) {
                    status = responseStatus;

                    if (status.getStatusCode() != 200) {
                        return State.ABORT;
                    }
                    return State.CONTINUE;
                }

                @Override
                public State onHeadersReceived(HttpHeaders headers) {
                    return State.CONTINUE;
                }

                @Override
                public Integer onCompleted() {
                    return status.getStatusCode();
                }
            });
            Integer statusCode = f.get(10, TimeUnit.SECONDS);
            assertNotNull(statusCode);
            assertEquals(statusCode.intValue(), 401);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void basicAuthTestPreemptiveTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            // send the request to the no-auth endpoint to be able to verify the
            // auth header is really sent preemptively for the initial call.
            Future<Response> f = client.prepareGet(getTargetUrlNoAuth())
                    .setRealm(basicAuthRealm(USER, ADMIN).setUsePreemptiveAuth(true).build())
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void basicAuthNegativeTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.prepareGet(getTargetUrl())
                    .setRealm(basicAuthRealm("fake", ADMIN).build())
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), 401);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void basicAuthInputStreamTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.preparePost(getTargetUrl())
                    .setBody(new ByteArrayInputStream("test".getBytes()))
                    .setRealm(basicAuthRealm(USER, ADMIN).build())
                    .execute();

            Response resp = f.get(30, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), "test");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void basicAuthFileTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.preparePost(getTargetUrl())
                    .setBody(SIMPLE_TEXT_FILE)
                    .setRealm(basicAuthRealm(USER, ADMIN).build())
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void basicAuthAsyncConfigTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRealm(basicAuthRealm(USER, ADMIN)))) {
            Future<Response> f = client.preparePost(getTargetUrl())
                    .setBody(SIMPLE_TEXT_FILE_STRING)
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void basicAuthFileNoKeepAliveTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(false))) {

            Future<Response> f = client.preparePost(getTargetUrl())
                    .setBody(SIMPLE_TEXT_FILE)
                    .setRealm(basicAuthRealm(USER, ADMIN).build())
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void noneAuthTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            BoundRequestBuilder r = client.prepareGet(getTargetUrl()).setRealm(basicAuthRealm(USER, ADMIN).build());

            Future<Response> f = r.execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }

    private static class RedirectHandler extends AbstractHandler {

        private static final Logger LOGGER = LoggerFactory.getLogger(RedirectHandler.class);

        @Override
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
                response.addHeader("X-" + CONTENT_LENGTH, String.valueOf(request.getContentLength()));
                byte[] b = "content".getBytes(UTF_8);
                response.setContentLength(b.length);
                response.getOutputStream().write(b);
            }
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }

    public static class SimpleHandler extends AbstractHandler {

        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {

            if (request.getHeader("X-401") != null) {
                response.setStatus(401);
                response.setContentLength(0);

            } else {
                response.addHeader("X-Auth", request.getHeader("Authorization"));
                response.addHeader("X-" + CONTENT_LENGTH, String.valueOf(request.getContentLength()));
                response.setIntHeader("X-" + CONTENT_LENGTH, request.getContentLength());
                response.setStatus(200);

                int size = 10 * 1024;
                byte[] bytes = new byte[size];
                int contentLength = 0;
                int read;
                do {
                    read = request.getInputStream().read(bytes);
                    if (read > 0) {
                        contentLength += read;
                        response.getOutputStream().write(bytes, 0, read);
                    }
                } while (read >= 0);
                response.setContentLength(contentLength);
            }
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }
}
