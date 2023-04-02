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
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test for multithreaded url fetcher calls that use two separate sets of ssl certificates. This then tests that the certificate settings do not clash (override each other),
 * resulting in the peer not authenticated exception
 *
 * @author dominict
 */
public class RedirectConnectionUsageTest extends AbstractBasicTest {

    private String baseUrl;
    private String servletEndpointRedirectUrl;

    @BeforeEach
    public void setUp() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.addServlet(new ServletHolder(new MockRedirectHttpServlet()), "/redirect/*");
        context.addServlet(new ServletHolder(new MockFullResponseHttpServlet()), "/*");
        server.setHandler(context);

        server.start();
        port1 = connector.getLocalPort();

        baseUrl = "http://localhost" + ':' + port1;
        servletEndpointRedirectUrl = baseUrl + "/redirect";
    }

    /**
     * Tests that after a redirect the final url in the response reflect the redirect
     */
    @Test
    public void testGetRedirectFinalUrl() throws Exception {
        registerRequest();

        AsyncHttpClientConfig config = config()
                .setKeepAlive(true)
                .setMaxConnectionsPerHost(1)
                .setMaxConnections(1)
                .setConnectTimeout(1000)
                .setRequestTimeout(1000)
                .setFollowRedirect(true)
                .build();

        try (AsyncHttpClient c = asyncHttpClient(config)) {
            ListenableFuture<Response> response = c.executeRequest(get(servletEndpointRedirectUrl));
            Response res = response.get();
            assertNotNull(res.getResponseBody());
            assertEquals(res.getUri().toString(), baseUrl + "/overthere");
        } finally {
            deregisterRequest();
        }
    }

    @SuppressWarnings("serial")
    static
    class MockRedirectHttpServlet extends HttpServlet {
        @Override
        public void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.sendRedirect("/overthere");
        }
    }

    @SuppressWarnings("serial")
    static
    class MockFullResponseHttpServlet extends HttpServlet {

        private static final String contentType = "text/xml";
        private static final String xml = "<?xml version=\"1.0\"?><hello date=\"%s\"></hello>";

        @Override
        public void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
            String xmlToReturn = String.format(xml, new Date());

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
