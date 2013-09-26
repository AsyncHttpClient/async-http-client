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

import static org.asynchttpclient.async.util.TestUtils.*;
import static org.testng.Assert.*;

import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public abstract class HttpToHttpsRedirectTest extends AbstractBasicTest {

    private final AtomicBoolean redirectDone = new AtomicBoolean(false);

    private class Relative302Handler extends AbstractHandler {

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

            if (r.getScheme().equalsIgnoreCase("https")) {
                httpResponse.addHeader("X-httpToHttps", "PASS");
                redirectDone.getAndSet(false);
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
        addHttpsConnector(server, port2);
        server.setHandler(new Relative302Handler());
        server.start();
        logger.info("Local HTTP server started successfully");
    }

    @Test(groups = { "standalone", "default_provider" })
    // FIXME find a way to make this threadsafe, other, set @Test(singleThreaded = true)
    public void runAllSequentiallyBecauseNotThreadSafe() throws Exception {
        httpToHttpsRedirect();
        httpToHttpsProperConfig();
        relativeLocationUrl();
    }

    // @Test(groups = { "standalone", "default_provider" })
    public void httpToHttpsRedirect() throws Exception {
        redirectDone.getAndSet(false);

        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setMaximumNumberOfRedirects(5).setFollowRedirects(true).build();
        AsyncHttpClient c = getAsyncHttpClient(cg);
        try {
            Response response = c.prepareGet(getTargetUrl()).setHeader("X-redirect", getTargetUrl2()).execute().get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getHeader("X-httpToHttps"), "PASS");
        } finally {
            c.close();
        }
    }

    // @Test(groups = { "standalone", "default_provider" })
    public void httpToHttpsProperConfig() throws Exception {
        redirectDone.getAndSet(false);

        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setMaximumNumberOfRedirects(5).setFollowRedirects(true).build();
        AsyncHttpClient c = getAsyncHttpClient(cg);
        try {
            Response response = c.prepareGet(getTargetUrl()).setHeader("X-redirect", getTargetUrl2() + "/test2").execute().get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getHeader("X-httpToHttps"), "PASS");

            // Test if the internal channel is downgraded to clean http.
            response = c.prepareGet(getTargetUrl()).setHeader("X-redirect", getTargetUrl2() + "/foo2").execute().get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getHeader("X-httpToHttps"), "PASS");
        } finally {
            c.close();
        }
    }

    // @Test(groups = { "standalone", "default_provider" })
    public void relativeLocationUrl() throws Exception {
        redirectDone.getAndSet(false);

        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setMaximumNumberOfRedirects(5).setFollowRedirects(true).build();
        AsyncHttpClient c = getAsyncHttpClient(cg);
        try {
            Response response = c.prepareGet(getTargetUrl()).setHeader("X-redirect", "/foo/test").execute().get();
            assertNotNull(response);
            assertEquals(response.getStatusCode(), 302);
            assertEquals(response.getUri().toString(), getTargetUrl());
        } finally {
            c.close();
        }
    }
}
