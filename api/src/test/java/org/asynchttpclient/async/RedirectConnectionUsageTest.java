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
import java.io.OutputStream;
import java.util.Date;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Test for multithreaded url fetcher calls that use two separate sets of ssl certificates. This then tests that the certificate settings do not clash (override each other),
 * resulting in the peer not authenticated exception
 * 
 * @author dominict
 */
public abstract class RedirectConnectionUsageTest extends AbstractBasicTest {
    private String BASE_URL;

    private String servletEndpointRedirectUrl;

    @BeforeClass
    public void setUp() throws Exception {
        port1 = findFreePort();
        server = newJettyHttpServer(port1);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.addServlet(new ServletHolder(new MockRedirectHttpServlet()), "/redirect/*");
        context.addServlet(new ServletHolder(new MockFullResponseHttpServlet()), "/*");
        server.setHandler(context);

        server.start();

        BASE_URL = "http://localhost" + ":" + port1;
        servletEndpointRedirectUrl = BASE_URL + "/redirect";
    }

    /**
     * Tests that after a redirect the final url in the response reflect the redirect
     */
    @Test
    public void testGetRedirectFinalUrl() throws Exception {

        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder()//
                .setAllowPoolingConnection(true)//
                .setMaximumConnectionsPerHost(1)//
                .setMaximumConnectionsTotal(1)//
                .setConnectionTimeoutInMs(1000)//
                .setRequestTimeoutInMs(1000)//
                .setFollowRedirects(true)//
                .build();

        AsyncHttpClient c = getAsyncHttpClient(config);
        try {
            Request r = new RequestBuilder("GET").setUrl(servletEndpointRedirectUrl).build();

            ListenableFuture<Response> response = c.executeRequest(r);
            Response res = null;
            res = response.get();
            assertNotNull(res.getResponseBody());
            assertEquals(res.getUri().toString(), BASE_URL + "/overthere");

        } finally {
            c.close();
        }
    }

    @SuppressWarnings("serial")
    class MockRedirectHttpServlet extends HttpServlet {
        public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
            res.sendRedirect("/overthere");
        }
    }

    @SuppressWarnings("serial")
    class MockFullResponseHttpServlet extends HttpServlet {

        private static final String contentType = "text/xml";
        private static final String xml = "<?xml version=\"1.0\"?><hello date=\"%s\"></hello>";

        public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
            String xmlToReturn = String.format(xml, new Object[] { new Date().toString() });

            res.setStatus(200);
            res.addHeader("Content-Type", contentType);
            res.addHeader("X-Method", req.getMethod());
            res.addHeader("MultiValue", "1");
            res.addHeader("MultiValue", "2");
            res.addHeader("MultiValue", "3");

            OutputStream os = res.getOutputStream();

            byte[] retVal = xmlToReturn.getBytes();
            res.setContentLength(retVal.length);
            os.write(retVal);
            os.close();
        }
    }
}
