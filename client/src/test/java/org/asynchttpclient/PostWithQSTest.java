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

import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.testng.Assert.*;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.test.TestUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

/**
 * Tests POST request with Query String.
 * 
 * @author Hubert Iwaniuk
 */
public class PostWithQSTest extends AbstractBasicTest {

    /**
     * POST with QS server part.
     */
    private class PostWithQSHandler extends AbstractHandler {
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if ("POST".equalsIgnoreCase(request.getMethod())) {
                String qs = request.getQueryString();
                if (isNonEmpty(qs) && request.getContentLength() == 3) {
                    ServletInputStream is = request.getInputStream();
                    response.setStatus(HttpServletResponse.SC_OK);
                    byte buf[] = new byte[is.available()];
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

    @Test(groups = "standalone")
    public void postWithQS() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.preparePost(String.format("http://%s:%d/?a=b", TestUtils.getUnitTestIpAddress(), port1)).setBody("abc".getBytes()).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }

    @Test(groups = "standalone")
    public void postWithNulParamQS() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.preparePost(String.format("http://%s:%d/?a=", TestUtils.getUnitTestIpAddress(), port1)).setBody("abc".getBytes()).execute(new AsyncCompletionHandlerBase() {

                @Override
                public State onStatusReceived(final HttpResponseStatus status) throws Exception {
                    if (!status.getUri().toUrl().equals(String.format("http://%s:%d/?a=", TestUtils.getUnitTestIpAddress(), port1))) {
                        throw new IOException(status.getUri().toUrl());
                    }
                    return super.onStatusReceived(status);
                }

            });
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }

    @Test(groups = "standalone")
    public void postWithNulParamsQS() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.preparePost(String.format("http://%s:%d/?a=b&c&d=e", TestUtils.getUnitTestIpAddress(), port1)).setBody("abc".getBytes()).execute(new AsyncCompletionHandlerBase() {

                @Override
                public State onStatusReceived(final HttpResponseStatus status) throws Exception {
                    if (!status.getUri().toUrl().equals(String.format("http://%s:%d/?a=b&c&d=e", TestUtils.getUnitTestIpAddress(), port1))) {
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

    @Test(groups = "standalone")
    public void postWithEmptyParamsQS() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.preparePost(String.format("http://%s:%d/?a=b&c=&d=e", TestUtils.getUnitTestIpAddress(), port1)).setBody("abc".getBytes()).execute(new AsyncCompletionHandlerBase() {

                @Override
                public State onStatusReceived(final HttpResponseStatus status) throws Exception {
                    if (!status.getUri().toUrl().equals(String.format("http://%s:%d/?a=b&c=&d=e", TestUtils.getUnitTestIpAddress(), port1))) {
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

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new PostWithQSHandler();
    }
}
