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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.*;
import static org.testng.Assert.*;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.test.TestUtils;
import org.asynchttpclient.uri.Uri;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.github.tomakehurst.wiremock.client.WireMock;

public class PerRequestRelative302TestLocal extends HttpServerTestBase {

    private final AtomicBoolean isSet = new AtomicBoolean(false);

    private class Relative302Handler extends AbstractHandler {

        public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

            String param;
            httpResponse.setContentType(TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
            Enumeration<?> headerNames = httpRequest.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                param = headerNames.nextElement().toString();

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
    }

    @Test(groups = "standalone")
    public void runAllSequentiallyBecauseNotThreadSafe() throws IOException, InterruptedException, ExecutionException {
        testRedirected302();
        testNotRedirected302();
        testRelativeLocationUrl();
        testRedirected302Invalid();
    }

    protected void testRedirected302() throws IOException, InterruptedException, ExecutionException {
    	WireMock.reset();
        stubFor(WireMock.get(urlEqualTo(mockServers.get("www.microsoft.com").getMockRelativeUrl())).willReturn(aResponse().withStatus(302).
                withHeader("Location", mockServers.get("www.microsoft.com").getMockRelativeUrl() + "/movedhere")));
        stubFor(WireMock.get(urlEqualTo(mockServers.get("www.microsoft.com").getMockRelativeUrl() + "/movedhere")).willReturn(aResponse().withStatus(200).
                withHeader("Content-Type", "application/json").withBody("")));

        isSet.getAndSet(false);
        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.prepareGet(getTargetUrl()).setFollowRedirect(true).setHeader("X-redirect", mockServers.get("www.microsoft.com").getMockUrl()).execute().get();

            assertNotNull(response, "No response received from client");
            assertEquals(response.getStatusCode(), 200, "Request was not successful");

            assertTrue(response.getUri().getPath().contains("/movedhere"), "Redirection didn't happen to the moved url (" + 
                    mockServers.get("www.google.com").getMockRelativeUrl() + "/movedhere" + "), got " + response.getUri().toUrl());
        }
    }

    protected void testNotRedirected302() throws IOException, InterruptedException, ExecutionException {
        isSet.getAndSet(false);
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            Response response = client.prepareGet(getTargetUrl()).setFollowRedirect(false).setHeader("X-redirect", "http://www.microsoft.com/").execute().get();
            assertNotNull(response, "No response received from client");
            assertEquals(response.getStatusCode(), 302, "Target webserver seems to have been moved temporarily");
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

    protected void testRedirected302Invalid() throws IOException, InterruptedException {
        isSet.getAndSet(false);
        try (AsyncHttpClient client = asyncHttpClient()) {
            // If the test hit a proxy, no ConnectException will be thrown and instead of 404 will be returned.
            Response response = client.preparePost(getTargetUrl()).setFollowRedirect(true).setHeader("X-redirect", String.format("http://%s:%d/", TestUtils.getLocalhostIp(), port2)).execute().get();

            assertNotNull(response, "No response received from client");
            assertEquals(response.getStatusCode(), 404, "Expected resource, not to be available");
        } catch (ExecutionException ex) {
            assertEquals(ex.getCause().getClass(), ConnectException.class);
        }
    }

    protected void testRelativeLocationUrl() throws IOException, InterruptedException, ExecutionException {
        isSet.getAndSet(false);

        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.preparePost(getTargetUrl()).setFollowRedirect(true).setHeader("X-redirect", "/foo/test").execute().get();
            assertNotNull(response, "No response received from client");
            assertEquals(response.getStatusCode(), 200, "Request was not successful");
            assertEquals(response.getUri().toString(), getTargetUrl(), "Response URL not equal to expected target URL");
        }
    }
}
