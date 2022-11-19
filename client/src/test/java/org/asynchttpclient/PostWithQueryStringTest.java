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

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests POST request with Query String.
 *
 * @author Hubert Iwaniuk
 */
public class PostWithQueryStringTest extends AbstractBasicTest {

    @Test
    public void postWithQueryString() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.preparePost("http://localhost:" + port1 + "/?a=b").setBody("abc".getBytes()).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }

    @Test
    public void postWithNullQueryParam() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.preparePost("http://localhost:" + port1 + "/?a=b&c&d=e").setBody("abc".getBytes()).execute(new AsyncCompletionHandlerBase() {

                @Override
                public State onStatusReceived(final HttpResponseStatus status) throws Exception {
                    if (!status.getUri().toUrl().equals("http://localhost:" + port1 + "/?a=b&c&d=e")) {
                        throw new IOException("failed to parse the query properly");
                    }
                    return super.onStatusReceived(status);
                }

            });
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }

    @Test
    public void postWithEmptyParamsQueryString() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.preparePost("http://localhost:" + port1 + "/?a=b&c=&d=e").setBody("abc".getBytes()).execute(new AsyncCompletionHandlerBase() {

                @Override
                public State onStatusReceived(final HttpResponseStatus status) throws Exception {
                    if (!status.getUri().toUrl().equals("http://localhost:" + port1 + "/?a=b&c=&d=e")) {
                        throw new IOException("failed to parse the query properly");
                    }
                    return super.onStatusReceived(status);
                }

            });
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }

    public static AbstractHandler configureHandler() throws Exception {
        return new PostWithQueryStringHandler();
    }

    /**
     * POST with QueryString server part.
     */
    private static class PostWithQueryStringHandler extends AbstractHandler {
        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if ("POST".equalsIgnoreCase(request.getMethod())) {
                String qs = request.getQueryString();
                if (isNonEmpty(qs) && request.getContentLength() == 3) {
                    ServletInputStream is = request.getInputStream();
                    response.setStatus(HttpServletResponse.SC_OK);
                    byte[] buf = new byte[is.available()];
                    is.readLine(buf, 0, is.available());
                    ServletOutputStream os = response.getOutputStream();
                    os.println(new String(buf));
                    os.flush();
                    os.close();
                } else {
                    response.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE);
                }
            } else { // this handler is to handle POST request
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
        }
    }
}
