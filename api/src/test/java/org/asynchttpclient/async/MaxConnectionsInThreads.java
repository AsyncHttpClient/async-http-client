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
 *
 */
package org.asynchttpclient.async;

import static org.asynchttpclient.async.util.TestUtils.*;
import static org.testng.Assert.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

abstract public class MaxConnectionsInThreads extends AbstractBasicTest {

    // FIXME weird
    private static URI servletEndpointUri;

    @Test(groups = { "online", "default_provider" })
    public void testMaxConnectionsWithinThreads() {

        String[] urls = new String[] { servletEndpointUri.toString(), servletEndpointUri.toString() };

        final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setConnectionTimeoutInMs(1000).setRequestTimeoutInMs(5000)
                .setAllowPoolingConnection(true).setMaximumConnectionsTotal(1).setMaximumConnectionsPerHost(1).build());

        try {
            final Boolean[] caughtError = new Boolean[] { Boolean.FALSE };
            List<Thread> ts = new ArrayList<Thread>();
            for (int i = 0; i < urls.length; i++) {
                final String url = urls[i];
                Thread t = new Thread() {
                    public void run() {
                        try {
                            client.prepareGet(url).execute();
                        } catch (IOException e) {
                            // assert that 2nd request fails, because maxTotalConnections=1
                            // logger.debug(i);
                            caughtError[0] = true;
                            System.err.println("============");
                            e.printStackTrace();
                            System.err.println("============");

                        }
                    }
                };
                t.start();
                ts.add(t);
            }

            for (Thread t : ts) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }

            // Let the threads finish
            try {
                Thread.sleep(4500);
            } catch (InterruptedException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            }

            assertTrue(caughtError[0], "Max Connections should have been reached");

            boolean errorInNotThread = false;
            for (int i = 0; i < urls.length; i++) {
                final String url = urls[i];
                try {
                    client.prepareGet(url).execute();
                    // client.prepareGet(url).execute();
                } catch (IOException e) {
                    // assert that 2nd request fails, because maxTotalConnections=1
                    // logger.debug(i);
                    errorInNotThread = true;
                    System.err.println("============");
                    e.printStackTrace();
                    System.err.println("============");
                }
            }
            // Let the request finish
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            }
            assertTrue(errorInNotThread, "Max Connections should have been reached");
        } finally {
            client.close();
        }
    }

    @Override
    @BeforeClass
    public void setUpGlobal() throws Exception {

        port1 = findFreePort();
        server = newJettyHttpServer(port1);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        context.addServlet(new ServletHolder(new MockTimeoutHttpServlet()), "/timeout/*");

        server.start();

        String endpoint = "http://127.0.0.1:" + port1 + "/timeout/";
        servletEndpointUri = new URI(endpoint);
    }

    public String getTargetUrl() {
        String s = "http://127.0.0.1:" + port1 + "/timeout/";
        try {
            servletEndpointUri = new URI(s);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return s;
    }

    @SuppressWarnings("serial")
    public static class MockTimeoutHttpServlet extends HttpServlet {
        private static final Logger LOGGER = LoggerFactory.getLogger(MockTimeoutHttpServlet.class);
        private static final String contentType = "text/plain";
        public static long DEFAULT_TIMEOUT = 2000;

        public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
            res.setStatus(200);
            res.addHeader("Content-Type", contentType);
            long sleepTime = DEFAULT_TIMEOUT;
            try {
                sleepTime = Integer.parseInt(req.getParameter("timeout"));

            } catch (NumberFormatException e) {
                sleepTime = DEFAULT_TIMEOUT;
            }

            try {
                LOGGER.debug("=======================================");
                LOGGER.debug("Servlet is sleeping for: " + sleepTime);
                LOGGER.debug("=======================================");
                Thread.sleep(sleepTime);
                LOGGER.debug("=======================================");
                LOGGER.debug("Servlet is awake for");
                LOGGER.debug("=======================================");
            } catch (Exception e) {

            }

            res.setHeader("XXX", "TripleX");

            byte[] retVal = "1".getBytes();
            OutputStream os = res.getOutputStream();

            res.setContentLength(retVal.length);
            os.write(retVal);
            os.close();
        }
    }
}
