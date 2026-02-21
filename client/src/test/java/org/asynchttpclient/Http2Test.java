/*
 *    Copyright (c) 2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient;

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for HTTP/2 support using Netty's native Http2FrameCodec.
 * Tests cover basic GET/POST, headers, body streaming, empty responses,
 * multiple concurrent requests (multiplexing), and error handling.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class Http2Test {

    private static final Logger LOGGER = LoggerFactory.getLogger(Http2Test.class);
    private static final int TIMEOUT = 30;

    private Http2TestServer server;
    private int port;

    @BeforeAll
    public void setUp() throws Exception {
        server = new Http2TestServer();
        server.start();
        port = server.getPort();
        LOGGER.info("HTTP/2 test server started on port {}", port);
    }

    @AfterAll
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private String getUrl(String path) {
        return "https://localhost:" + port + path;
    }

    private DefaultAsyncHttpClientConfig.Builder http2Config() {
        return config()
                .setEnableHttp2(true)
                .setUseInsecureTrustManager(true)
                .setDisableHttpsEndpointIdentificationAlgorithm(true);
    }

    // ==================== Basic GET Tests ====================

    @Test
    public void testSimpleGet() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            Response response = client.prepareGet(getUrl("/"))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("Hello HTTP/2 World", response.getResponseBody());
        }
    }

    @Test
    public void testGetWithEcho() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            Response response = client.prepareGet(getUrl("/echo"))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("No Body", response.getResponseBody());
            assertEquals("GET", response.getHeader("x-method"));
        }
    }

    @Test
    public void testGetEmptyResponse() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            Response response = client.prepareGet(getUrl("/empty"))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(204, response.getStatusCode());
        }
    }

    // ==================== POST Tests ====================

    @Test
    public void testPostWithBody() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            String requestBody = "Hello from HTTP/2 client";
            Response response = client.preparePost(getUrl("/echo"))
                    .setBody(requestBody)
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals(requestBody, response.getResponseBody());
            assertEquals("POST", response.getHeader("x-method"));
        }
    }

    @Test
    public void testPostWithLargeBody() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                sb.append("Line ").append(i).append(": Large body content for HTTP/2 test\n");
            }
            String requestBody = sb.toString();

            Response response = client.preparePost(getUrl("/echo"))
                    .setBody(requestBody)
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals(requestBody, response.getResponseBody());
        }
    }

    // ==================== Headers Tests ====================

    @Test
    public void testCustomHeaders() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            Response response = client.prepareGet(getUrl("/headers"))
                    .addHeader("X-Test-Header", "test-value")
                    .addHeader("Accept", "application/json")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("custom-value", response.getHeader("x-custom-header"));
            assertNotNull(response.getHeader("content-type"));
        }
    }

    @Test
    public void testResponseHeaders() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            Response response = client.prepareGet(getUrl("/headers"))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("custom-value", response.getHeader("x-custom-header"));
            // Check multi-value header
            List<String> values = response.getHeaders().getAll("x-multi-value");
            assertTrue(values.contains("value1"));
            assertTrue(values.contains("value2"));
        }
    }

    // ==================== Large Response Test ====================

    @Test
    public void testLargeResponse() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            Response response = client.prepareGet(getUrl("/large"))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            String body = response.getResponseBody();
            assertTrue(body.contains("Line 0:"));
            assertTrue(body.contains("Line 999:"));
            assertTrue(body.length() > 50000);
        }
    }

    // ==================== AsyncHandler Tests ====================

    @Test
    public void testAsyncHandlerCallbacks() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            AtomicBoolean statusReceived = new AtomicBoolean(false);
            AtomicBoolean headersReceived = new AtomicBoolean(false);
            AtomicBoolean bodyReceived = new AtomicBoolean(false);
            AtomicInteger statusCode = new AtomicInteger(-1);
            AtomicReference<String> contentType = new AtomicReference<>();

            String result = client.prepareGet(getUrl("/echo"))
                    .execute(new AsyncHandler<String>() {
                        private final StringBuilder body = new StringBuilder();

                        @Override
                        public State onStatusReceived(HttpResponseStatus responseStatus) {
                            statusReceived.set(true);
                            statusCode.set(responseStatus.getStatusCode());
                            return State.CONTINUE;
                        }

                        @Override
                        public State onHeadersReceived(HttpHeaders headers) {
                            headersReceived.set(true);
                            contentType.set(headers.get("content-type"));
                            return State.CONTINUE;
                        }

                        @Override
                        public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
                            bodyReceived.set(true);
                            body.append(new String(bodyPart.getBodyPartBytes()));
                            return State.CONTINUE;
                        }

                        @Override
                        public String onCompleted() {
                            return body.toString();
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                            LOGGER.error("AsyncHandler error", t);
                        }
                    }).get(TIMEOUT, TimeUnit.SECONDS);

            assertTrue(statusReceived.get(), "onStatusReceived should have been called");
            assertTrue(headersReceived.get(), "onHeadersReceived should have been called");
            assertTrue(bodyReceived.get(), "onBodyPartReceived should have been called");
            assertEquals(200, statusCode.get());
            assertEquals("text/plain", contentType.get());
            assertEquals("No Body", result);
        }
    }

    @Test
    public void testAsyncHandlerAbortOnStatus() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            AtomicBoolean headersReceived = new AtomicBoolean(false);
            AtomicBoolean bodyReceived = new AtomicBoolean(false);

            String result = client.prepareGet(getUrl("/echo"))
                    .execute(new AsyncHandler<String>() {
                        @Override
                        public State onStatusReceived(HttpResponseStatus responseStatus) {
                            return State.ABORT;
                        }

                        @Override
                        public State onHeadersReceived(HttpHeaders headers) {
                            headersReceived.set(true);
                            return State.CONTINUE;
                        }

                        @Override
                        public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
                            bodyReceived.set(true);
                            return State.CONTINUE;
                        }

                        @Override
                        public String onCompleted() {
                            return "aborted";
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                        }
                    }).get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals("aborted", result);
            assertFalse(headersReceived.get(), "onHeadersReceived should NOT have been called after ABORT");
            assertFalse(bodyReceived.get(), "onBodyPartReceived should NOT have been called after ABORT");
        }
    }

    // ==================== Multiplexing Test ====================

    @Test
    public void testMultipleConcurrentRequests() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            int numRequests = 10;
            List<ListenableFuture<Response>> futures = new ArrayList<>();

            for (int i = 0; i < numRequests; i++) {
                ListenableFuture<Response> future = client.prepareGet(getUrl("/echo"))
                        .addHeader("X-Request-Id", String.valueOf(i))
                        .execute();
                futures.add(future);
            }

            for (int i = 0; i < numRequests; i++) {
                Response response = futures.get(i).get(TIMEOUT, TimeUnit.SECONDS);
                assertEquals(200, response.getStatusCode());
                assertEquals("No Body", response.getResponseBody());
            }
        }
    }

    // ==================== Redirect Test ====================

    @Test
    public void testRedirect() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config().setFollowRedirect(true).setMaxRedirects(5))) {
            Response response = client.prepareGet(getUrl("/redirect"))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("Redirect Target Reached", response.getResponseBody());
        }
    }

    @Test
    public void testRedirectNotFollowed() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config().setFollowRedirect(false))) {
            Response response = client.prepareGet(getUrl("/redirect"))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(302, response.getStatusCode());
        }
    }

    // ==================== AsyncCompletionHandler Test ====================

    @Test
    public void testAsyncCompletionHandler() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            Response response = client.prepareGet(getUrl("/echo"))
                    .execute(new AsyncCompletionHandler<Response>() {
                        @Override
                        public Response onCompleted(Response response) {
                            return response;
                        }
                    }).get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("No Body", response.getResponseBody());
        }
    }

    // ==================== POST with form params ====================

    @Test
    public void testPostWithFormParams() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            Response response = client.preparePost(getUrl("/echo"))
                    .addFormParam("key1", "value1")
                    .addFormParam("key2", "value2")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            String responseBody = response.getResponseBody();
            assertTrue(responseBody.contains("key1=value1"));
            assertTrue(responseBody.contains("key2=value2"));
        }
    }

    // ==================== Multiple sequential requests ====================

    @Test
    public void testSequentialRequestsOnSameConnection() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            Response response1 = client.prepareGet(getUrl("/echo"))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response1.getStatusCode());

            Response response2 = client.prepareGet(getUrl("/headers"))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response2.getStatusCode());
            assertEquals("custom-value", response2.getHeader("x-custom-header"));

            Response response3 = client.preparePost(getUrl("/echo"))
                    .setBody("Test body")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, response3.getStatusCode());
            assertEquals("Test body", response3.getResponseBody());
        }
    }

    // ==================== PUT and DELETE Tests ====================

    @Test
    public void testPutRequest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            Response response = client.preparePut(getUrl("/echo"))
                    .setBody("PUT body")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("PUT body", response.getResponseBody());
            assertEquals("PUT", response.getHeader("x-method"));
        }
    }

    @Test
    public void testDeleteRequest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(http2Config())) {
            Response response = client.prepareDelete(getUrl("/echo"))
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("DELETE", response.getHeader("x-method"));
        }
    }
}
