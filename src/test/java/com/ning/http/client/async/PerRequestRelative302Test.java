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

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Response;
import com.ning.http.client.uri.Uri;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public abstract class PerRequestRelative302Test extends AbstractBasicTest {

    // FIXME super NOT threadsafe!!!
    private final AtomicBoolean isSet = new AtomicBoolean(false);

    private class Relative302Handler extends AbstractHandler {

        public void handle(String s, Request r, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

            String param;
            httpResponse.setContentType("text/html; charset=utf-8");
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

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        server = new Server();

        port1 = findFreePort();
        port2 = findFreePort();

        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(port1);
        server.addConnector(listener);

        server.setHandler(new Relative302Handler());
        server.start();
        log.info("Local HTTP server started successfully");
    }

    @Test(groups = { "online", "default_provider" })
    public void redirected302Test() throws Throwable {
        isSet.getAndSet(false);
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            // once
            Response response = client.prepareGet(getTargetUrl()).setFollowRedirects(true).setHeader("X-redirect", "http://www.microsoft.com/").execute().get();

            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);

            String anyMicrosoftPage = "https://www.microsoft.com[^:]*:443";
            String baseUrl = getBaseUrl(response.getUri());

            assertTrue(baseUrl.matches(anyMicrosoftPage), "response does not show redirection to " + anyMicrosoftPage);
        }
    }

    @Test(groups = { "online", "default_provider" })
    public void notRedirected302Test() throws Throwable {
        isSet.getAndSet(false);
        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setFollowRedirect(true).build();
        try (AsyncHttpClient client = getAsyncHttpClient(cg)) {
            // once
            Response response = client.prepareGet(getTargetUrl()).setFollowRedirects(false).setHeader("X-redirect", "http://www.microsoft.com/").execute().get();

            assertNotNull(response);
            assertEquals(response.getStatusCode(), 302);
        }
    }

    private String getBaseUrl(Uri uri) {
        String url = uri.toString();
        int port = uri.getPort();
        if (port == -1) {
            port = getPort(uri);
            url = url.substring(0, url.length() - 1) + ":" + port;
        }
        return url.substring(0, url.lastIndexOf(":") + String.valueOf(port).length() + 1);
    }

    private static int getPort(Uri uri) {
        int port = uri.getPort();
        if (port == -1)
            port = uri.getScheme().equals("http") ? 80 : 443;
        return port;
    }

    @Test(groups = { "standalone", "default_provider" })
    public void redirected302InvalidTest() throws Throwable {
        isSet.getAndSet(false);

        // If the test hit a proxy, no ConnectException will be thrown and instead of 404 will be returned.
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            Response response = client.preparePost(getTargetUrl()).setFollowRedirects(true).setHeader("X-redirect", String.format("http://127.0.0.1:%d/", port2)).execute().get();

            assertNotNull(response);
            assertEquals(response.getStatusCode(), 404);
        } catch (ExecutionException ex) {
            assertEquals(ex.getCause().getClass(), ConnectException.class);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void relativeLocationUrl() throws Throwable {
        isSet.getAndSet(false);

        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            Response response = client.preparePost(getTargetUrl()).setFollowRedirects(true).setHeader("X-redirect", "/foo/test").execute().get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getUri().toString(), getTargetUrl());
        }
    }
}
