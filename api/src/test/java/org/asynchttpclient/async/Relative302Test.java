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

import static org.asynchttpclient.async.util.TestUtils.TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET;
import static org.asynchttpclient.async.util.TestUtils.findFreePort;
import static org.asynchttpclient.async.util.TestUtils.newJettyHttpServer;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.asynchttpclient.uri.UriComponents;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public abstract class Relative302Test extends AbstractBasicTest {
    private final AtomicBoolean isSet = new AtomicBoolean(false);

    private class Relative302Handler extends AbstractHandler {

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

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        port1 = findFreePort();
        port2 = findFreePort();
        server = newJettyHttpServer(port1);
        server.setHandler(new Relative302Handler());
        server.start();
        logger.info("Local HTTP server started successfully");
    }

    @Test(groups = { "online", "default_provider" })
    public void testAllSequentiallyBecauseNotThreadSafe() throws Exception {
        redirected302Test();
        redirected302InvalidTest();
        absolutePathRedirectTest();
        relativePathRedirectTest();
    }

    // @Test(groups = { "online", "default_provider" })
    public void redirected302Test() throws Exception {
        isSet.getAndSet(false);
        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setFollowRedirect(true).build();
        AsyncHttpClient c = getAsyncHttpClient(cg);

        try {
            Response response = c.prepareGet(getTargetUrl()).setHeader("X-redirect", "http://www.google.com/").execute().get();

            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);

            String baseUrl = getBaseUrl(response.getUri());

            assertTrue(baseUrl.startsWith("http://www.google."), "response does not show redirection to a google subdomain, got " + baseUrl);
        } finally {
            c.close();
        }
    }

    // @Test(groups = { "standalone", "default_provider" })
    public void redirected302InvalidTest() throws Exception {
        isSet.getAndSet(false);
        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setFollowRedirect(true).build();
        AsyncHttpClient c = getAsyncHttpClient(cg);

        // If the test hit a proxy, no ConnectException will be thrown and instead of 404 will be returned.
        try {
            Response response = c.prepareGet(getTargetUrl()).setHeader("X-redirect", String.format("http://127.0.0.1:%d/", port2)).execute().get();

            assertNotNull(response);
            assertEquals(response.getStatusCode(), 404);
        } catch (ExecutionException ex) {
            assertEquals(ex.getCause().getClass(), ConnectException.class);
        } finally {
            c.close();
        }
    }

    // @Test(groups = { "standalone", "default_provider" })
    public void absolutePathRedirectTest() throws Exception {
        isSet.getAndSet(false);

        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setFollowRedirect(true).build();
        AsyncHttpClient c = getAsyncHttpClient(cg);
        try {
            String redirectTarget = "/bar/test";
            String destinationUrl = new URI(getTargetUrl()).resolve(redirectTarget).toString();

            Response response = c.prepareGet(getTargetUrl()).setHeader("X-redirect", redirectTarget).execute().get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getUri().toString(), destinationUrl);

            logger.debug("{} was redirected to {}", redirectTarget, destinationUrl);
        } finally {
            c.close();
        }
    }

    // @Test(groups = { "standalone", "default_provider" })
    public void relativePathRedirectTest() throws Exception {
        isSet.getAndSet(false);

        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setFollowRedirect(true).build();
        AsyncHttpClient c = getAsyncHttpClient(cg);
        try {
            String redirectTarget = "bar/test1";
            String destinationUrl = new URI(getTargetUrl()).resolve(redirectTarget).toString();

            Response response = c.prepareGet(getTargetUrl()).setHeader("X-redirect", redirectTarget).execute().get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getUri().toString(), destinationUrl);

            logger.debug("{} was redirected to {}", redirectTarget, destinationUrl);
        } finally {
            c.close();
        }
    }

    private String getBaseUrl(UriComponents uri) {
        String url = uri.toString();
        int port = uri.getPort();
        if (port == -1) {
            port = getPort(uri);
            url = url.substring(0, url.length() - 1) + ":" + port;
        }
        return url.substring(0, url.lastIndexOf(":") + String.valueOf(port).length() + 1);
    }

    private static int getPort(UriComponents uri) {
        int port = uri.getPort();
        if (port == -1)
            port = uri.getScheme().equals("http") ? 80 : 443;
        return port;
    }
}
