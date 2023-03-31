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

import org.junit.jupiter.api.Test;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.uri.Uri;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.findFreePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PerRequestRelative302Test extends AbstractBasicTest {

    // FIXME super NOT threadsafe!!!
    private static final AtomicBoolean isSet = new AtomicBoolean(false);

    private static int getPort(Uri uri) {
        int port = uri.getPort();
        if (port == -1) {
            port = "http".equals(uri.getScheme()) ? 80 : 443;
        }
        return port;
    }

    @Override
    @BeforeEach
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);

        server.setHandler(new Relative302Handler());
        server.start();
        port1 = connector.getLocalPort();
        logger.info("Local HTTP server started successfully");
        port2 = findFreePort();
    }

    @Test
    // FIXME threadsafe
    public void runAllSequentiallyBecauseNotThreadSafe() throws Exception {
        redirected302Test();
        notRedirected302Test();
        relativeLocationUrl();
        redirected302InvalidTest();
    }

    @Test
    public void redirected302Test() throws Exception {
        isSet.getAndSet(false);
        try (AsyncHttpClient c = asyncHttpClient()) {
            Response response = c.prepareGet(getTargetUrl()).setFollowRedirect(true).setHeader("X-redirect", "https://www.microsoft.com/").execute().get();

            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);

            String anyMicrosoftPage = "https://www.microsoft.com[^:]*:443";
            String baseUrl = getBaseUrl(response.getUri());

            assertTrue(baseUrl.matches(anyMicrosoftPage), "response does not show redirection to " + anyMicrosoftPage);
        }
    }

    @Test
    public void notRedirected302Test() throws Exception {
        isSet.getAndSet(false);
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            Response response = c.prepareGet(getTargetUrl()).setFollowRedirect(false).setHeader("X-redirect", "http://www.microsoft.com/").execute().get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 302);
        }
    }

    private static String getBaseUrl(Uri uri) {
        String url = uri.toString();
        int port = uri.getPort();
        if (port == -1) {
            port = getPort(uri);
            url = url.substring(0, url.length() - 1) + ':' + port;
        }
        return url.substring(0, url.lastIndexOf(':') + String.valueOf(port).length() + 1);
    }

    @Test
    public void redirected302InvalidTest() throws Exception {
        isSet.getAndSet(false);
        Exception e = null;

        try (AsyncHttpClient c = asyncHttpClient()) {
            c.preparePost(getTargetUrl()).setFollowRedirect(true).setHeader("X-redirect", String.format("http://localhost:%d/", port2)).execute().get();
        } catch (ExecutionException ex) {
            e = ex;
        }

        assertNotNull(e);
        Throwable cause = e.getCause();
        assertTrue(cause instanceof ConnectException);
        assertTrue(cause.getMessage().contains(":" + port2));
    }

    @Test
    public void relativeLocationUrl() throws Exception {
        isSet.getAndSet(false);

        try (AsyncHttpClient c = asyncHttpClient()) {
            Response response = c.preparePost(getTargetUrl()).setFollowRedirect(true).setHeader("X-redirect", "/foo/test").execute().get();
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

                if (param.startsWith("X-redirect") && !isSet.getAndSet(true)) {
                    httpResponse.addHeader("Location", httpRequest.getHeader(param));
                    httpResponse.setStatus(302);
                    httpResponse.getOutputStream().flush();
                    httpResponse.getOutputStream().close();
                    return;
                }
            }
            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();
        }
    }
}
