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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HttpToHttpsRedirectTest extends AbstractBasicTest {

    // FIXME super NOT threadsafe!!!
    private static final AtomicBoolean redirectDone = new AtomicBoolean(false);

    @Override
    @BeforeEach
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector1 = addHttpConnector(server);
        ServerConnector connector2 = addHttpsConnector(server);
        server.setHandler(new Relative302Handler());
        server.start();
        port1 = connector1.getLocalPort();
        port2 = connector2.getLocalPort();
        logger.info("Local HTTP server started successfully");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    // FIXME find a way to make this threadsafe, other, set @RepeatedIfExceptionsTest(repeats = 5)(singleThreaded = true)
    public void runAllSequentiallyBecauseNotThreadSafe() throws Exception {
        httpToHttpsRedirect();
        httpToHttpsProperConfig();
        relativeLocationUrl();
    }

    //    @Disabled
    @RepeatedIfExceptionsTest(repeats = 5)
    public void httpToHttpsRedirect() throws Exception {
        redirectDone.getAndSet(false);

        AsyncHttpClientConfig cg = config()
                .setMaxRedirects(5)
                .setFollowRedirect(true)
                .setUseInsecureTrustManager(true)
                .build();
        try (AsyncHttpClient c = asyncHttpClient(cg)) {
            Response response = c.prepareGet(getTargetUrl()).setHeader("X-redirect", getTargetUrl2()).execute().get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getHeader("X-httpToHttps"), "PASS");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void httpToHttpsProperConfig() throws Exception {
        redirectDone.getAndSet(false);

        AsyncHttpClientConfig cg = config()
                .setMaxRedirects(5)
                .setFollowRedirect(true)
                .setUseInsecureTrustManager(true)
                .build();
        try (AsyncHttpClient c = asyncHttpClient(cg)) {
            Response response = c.prepareGet(getTargetUrl()).setHeader("X-redirect", getTargetUrl2() + "/test2").execute().get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getHeader("X-httpToHttps"), "PASS");

            // Test if the internal channel is downgraded to clean http.
            response = c.prepareGet(getTargetUrl()).setHeader("X-redirect", getTargetUrl2() + "/foo2").execute().get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getHeader("X-httpToHttps"), "PASS");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void relativeLocationUrl() throws Exception {
        redirectDone.getAndSet(false);

        AsyncHttpClientConfig cg = config()
                .setMaxRedirects(5)
                .setFollowRedirect(true)
                .setUseInsecureTrustManager(true)
                .build();
        try (AsyncHttpClient c = asyncHttpClient(cg)) {
            Response response = c.prepareGet(getTargetUrl()).setHeader("X-redirect", "/foo/test").execute().get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getUri().toString(), getTargetUrl());
        }
    }

    private static class Relative302Handler extends AbstractHandler {

        @Override
        public void handle(String s, Request r, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

            String param;
            httpResponse.setContentType(TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
            Enumeration<?> e = httpRequest.getHeaderNames();
            while (e.hasMoreElements()) {
                param = e.nextElement().toString();

                if (param.startsWith("X-redirect") && !redirectDone.getAndSet(true)) {
                    httpResponse.addHeader("Location", httpRequest.getHeader(param));
                    httpResponse.setStatus(302);
                    httpResponse.getOutputStream().flush();
                    httpResponse.getOutputStream().close();
                    return;
                }
            }

            if ("https".equalsIgnoreCase(r.getScheme())) {
                httpResponse.addHeader("X-httpToHttps", "PASS");
                redirectDone.getAndSet(false);
            }

            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();
        }
    }
}
