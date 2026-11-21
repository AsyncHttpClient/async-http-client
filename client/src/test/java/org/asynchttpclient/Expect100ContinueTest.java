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

import io.netty.handler.codec.http.HttpHeaderValues;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.ResponseFilter;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.EXPECT;
import static io.netty.handler.codec.http.HttpHeaderNames.WWW_AUTHENTICATE;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.ADMIN;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE_STRING;
import static org.asynchttpclient.test.TestUtils.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test the Expect: 100-Continue.
 */
public class Expect100ContinueTest extends AbstractBasicTest {

    private static final String REPLAYED = "X-Replayed";

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ZeroCopyHandler();
    }

    @Test
    public void Expect100Continue() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.preparePut("http://localhost:" + port1 + '/')
                    .setHeader(EXPECT, HttpHeaderValues.CONTINUE)
                    .setBody(SIMPLE_TEXT_FILE)
                    .execute();
            Response resp = f.get();
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        }
    }

    @Test
    public void basicAuthRetryAfter100ContinueSendsHeaders() throws Exception {
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        ResponseFilter statusRecorder = new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                statuses.add(ctx.getResponseStatus().getStatusCode());
                return ctx;
            }
        };
        try (AsyncHttpClient client = asyncHttpClient(config().addResponseFilter(statusRecorder))) {
            Response resp = client.preparePut("http://localhost:" + port1 + "/auth")
                    .setHeader(EXPECT, HttpHeaderValues.CONTINUE)
                    .setRealm(basicAuthRealm(USER, ADMIN))
                    .setBody(SIMPLE_TEXT_FILE)
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(List.of(100, 401), statuses.subList(0, 2));
            assertEquals(HttpServletResponse.SC_OK, resp.getStatusCode());
            assertEquals(SIMPLE_TEXT_FILE_STRING, resp.getResponseBody());
        }
    }

    @Test
    public void filterReplayAfter100ContinueSendsHeaders() throws Exception {
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        ResponseFilter replayOnUnavailable = new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                int status = ctx.getResponseStatus().getStatusCode();
                statuses.add(status);
                if (status == HttpServletResponse.SC_SERVICE_UNAVAILABLE) {
                    return new FilterContext.FilterContextBuilder<T>(ctx.getAsyncHandler(),
                            ctx.getRequest().toBuilder().setHeader(REPLAYED, "true").build())
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };
        try (AsyncHttpClient client = asyncHttpClient(config().addResponseFilter(replayOnUnavailable))) {
            Response resp = client.preparePut("http://localhost:" + port1 + "/replay")
                    .setHeader(EXPECT, HttpHeaderValues.CONTINUE)
                    .setBody(SIMPLE_TEXT_FILE)
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(List.of(100, 503), statuses.subList(0, 2));
            assertEquals(HttpServletResponse.SC_OK, resp.getStatusCode());
            assertEquals(SIMPLE_TEXT_FILE_STRING, resp.getResponseBody());
        }
    }

    private static class ZeroCopyHandler extends AbstractHandler {

        @Override
        public void handle(String s, Request r, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

            if (s.endsWith("/auth") || s.endsWith("/replay")) {
                // Reading the input sends the 100 Continue, so the 401 or 503 follows it.
                byte[] body = IOUtils.toByteArray(httpRequest.getInputStream());
                if (s.endsWith("/auth") && httpRequest.getHeader(AUTHORIZATION.toString()) == null) {
                    httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    httpResponse.setHeader(WWW_AUTHENTICATE.toString(), "Basic realm=\"test\"");
                    httpResponse.setContentLength(0);
                } else if (s.endsWith("/replay") && httpRequest.getHeader(REPLAYED) == null) {
                    httpResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    httpResponse.setContentLength(0);
                } else {
                    httpResponse.setStatus(HttpServletResponse.SC_OK);
                    httpResponse.setContentLength(body.length);
                    httpResponse.getOutputStream().write(body);
                }
                r.setHandled(true);
                return;
            }

            int size = 10 * 1024;
            if (httpRequest.getContentLength() > 0) {
                size = httpRequest.getContentLength();
            }
            byte[] bytes = new byte[size];
            if (bytes.length > 0) {
                final int read = httpRequest.getInputStream().read(bytes);
                httpResponse.getOutputStream().write(bytes, 0, read);
            }

            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
        }
    }
}
