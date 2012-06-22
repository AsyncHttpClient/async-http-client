/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.netty;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static org.testng.Assert.assertTrue;


public class RetryNonBlockingIssue {

    private URI servletEndpointUri;

    private Server server;

    private int port1;

    public static int findFreePort() throws IOException {
        ServerSocket socket = null;

        try {
            // 0 is open a socket on any free port
            socket = new ServerSocket(0);
            return socket.getLocalPort();
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }


    @BeforeMethod
    public void setUp() throws Exception {
        server = new Server();

        port1 = findFreePort();

        Connector listener = new SelectChannelConnector();
        listener.setHost("127.0.0.1");
        listener.setPort(port1);

        server.addConnector(listener);


        ServletContextHandler context = new
                ServletContextHandler(ServletContextHandler.SESSIONS);

        context.setContextPath("/");
        server.setHandler(context);
        context.addServlet(new ServletHolder(new
                MockExceptionServlet()), "/*");

        server.start();

        servletEndpointUri = new URI("http://127.0.0.1:" + port1 + "/");
    }

    @AfterMethod
    public void stop() {

        try {
            if (server != null) server.stop();
        } catch (Exception e) {
        }


    }

    private ListenableFuture<Response> testMethodRequest(AsyncHttpClient
            fetcher, int requests, String action, String id) throws IOException {
        RequestBuilder builder = new RequestBuilder("GET");
        builder.addQueryParameter(action, "1");

        builder.addQueryParameter("maxRequests", "" + requests);
        builder.addQueryParameter("id", id);
        builder.setUrl(servletEndpointUri.toString());
        com.ning.http.client.Request r = builder.build();
        return fetcher.executeRequest(r);

    }

    /**
     * Tests that a head request can be made
     *
     * @throws IOException
     * @throws ExecutionException
     * @throws InterruptedException
     */
    @Test
    public void testRetryNonBlocking() throws IOException, InterruptedException,
            ExecutionException {
        AsyncHttpClient c = null;
        List<ListenableFuture<Response>> res = new
                ArrayList<ListenableFuture<Response>>();
        try {
            AsyncHttpClientConfig.Builder bc =
                    new AsyncHttpClientConfig.Builder();

            bc.setAllowPoolingConnection(true);
            bc.setMaximumConnectionsTotal(100);
            bc.setConnectionTimeoutInMs(60000);
            bc.setRequestTimeoutInMs(30000);

            NettyAsyncHttpProviderConfig config = new
                    NettyAsyncHttpProviderConfig();

            bc.setAsyncHttpClientProviderConfig(config);
            c = new AsyncHttpClient(bc.build());

            for (int i = 0; i < 32; i++) {
                res.add(testMethodRequest(c, 3, "servlet", UUID.randomUUID().toString()));
            }

            StringBuilder b = new StringBuilder();
            for (ListenableFuture<Response> r : res) {
                Response theres = r.get();
                b.append("==============\r\n");
                b.append("Response Headers\r\n");
                Map<String, List<String>> heads = theres.getHeaders();
                b.append(heads + "\r\n");
                b.append("==============\r\n");
                assertTrue(heads.size() > 0);
            }
            System.out.println(b.toString());
            System.out.flush();

        }
        finally {
            if (c != null) c.close();
        }
    }

    @Test
    public void testRetryNonBlockingAsyncConnect() throws IOException, InterruptedException,
            ExecutionException {
        AsyncHttpClient c = null;
        List<ListenableFuture<Response>> res = new
                ArrayList<ListenableFuture<Response>>();
        try {
            AsyncHttpClientConfig.Builder bc =
                    new AsyncHttpClientConfig.Builder();

            bc.setAllowPoolingConnection(true);
            bc.setMaximumConnectionsTotal(100);
            bc.setConnectionTimeoutInMs(60000);
            bc.setRequestTimeoutInMs(30000);

            NettyAsyncHttpProviderConfig config = new
                    NettyAsyncHttpProviderConfig();
            config.addProperty(NettyAsyncHttpProviderConfig.EXECUTE_ASYNC_CONNECT, "true");

            bc.setAsyncHttpClientProviderConfig(config);
            c = new AsyncHttpClient(bc.build());

            for (int i = 0; i < 32; i++) {
                res.add(testMethodRequest(c, 3, "servlet", UUID.randomUUID().toString()));
            }

            StringBuilder b = new StringBuilder();
            for (ListenableFuture<Response> r : res) {
                Response theres = r.get();
                b.append("==============\r\n");
                b.append("Response Headers\r\n");
                Map<String, List<String>> heads = theres.getHeaders();
                b.append(heads + "\r\n");
                b.append("==============\r\n");
                assertTrue(heads.size() > 0);
            }
            System.out.println(b.toString());
            System.out.flush();

        }
        finally {
            if (c != null) c.close();
        }
    }

    @Test
    public void testRetryBlocking() throws IOException, InterruptedException,
            ExecutionException {
        AsyncHttpClient c = null;
        List<ListenableFuture<Response>> res = new
                ArrayList<ListenableFuture<Response>>();
        try {
            AsyncHttpClientConfig.Builder bc =
                    new AsyncHttpClientConfig.Builder();

            bc.setAllowPoolingConnection(true);
            bc.setMaximumConnectionsTotal(100);
            bc.setConnectionTimeoutInMs(30000);
            bc.setRequestTimeoutInMs(30000);

            NettyAsyncHttpProviderConfig config = new
                    NettyAsyncHttpProviderConfig();
            config.addProperty(NettyAsyncHttpProviderConfig.USE_BLOCKING_IO, "true");

            bc.setAsyncHttpClientProviderConfig(config);
            c = new AsyncHttpClient(bc.build());

            for (int i = 0; i < 32; i++) {
                res.add(testMethodRequest(c, 3, "servlet", UUID.randomUUID().toString()));
            }

            StringBuilder b = new StringBuilder();
            for (ListenableFuture<Response> r : res) {
                Response theres = r.get();
                b.append("==============\r\n");
                b.append("Response Headers\r\n");
                Map<String, List<String>> heads = theres.getHeaders();
                b.append(heads + "\r\n");
                b.append("==============\r\n");
                assertTrue(heads.size() > 0);

            }
            System.out.println(b.toString());
            System.out.flush();

        }
        finally {
            if (c != null) c.close();
        }
    }

    @SuppressWarnings("serial")
    public class MockExceptionServlet extends HttpServlet {

        private Map<String, Integer> requests = new
                ConcurrentHashMap<String, Integer>();

        private synchronized int increment(String id) {
            int val = 0;
            if (requests.containsKey(id)) {
                Integer i = requests.get(id);
                val = i + 1;
                requests.put(id, val);
            } else {
                requests.put(id, 1);
                val = 1;
            }
            System.out.println("REQUESTS: " + requests);
            return val;
        }

        public void service(HttpServletRequest req, HttpServletResponse res)
                throws ServletException, IOException {
            String maxRequests = req.getParameter("maxRequests");
            int max = 0;
            try {
                max = Integer.parseInt(maxRequests);
            }
            catch (NumberFormatException e) {
                max = 3;
            }
            String id = req.getParameter("id");
            int requestNo = increment(id);
            String servlet = req.getParameter("servlet");
            String io = req.getParameter("io");
            String error = req.getParameter("500");


            if (requestNo >= max) {
                res.setHeader("Success-On-Attempt", "" + requestNo);
                res.setHeader("id", id);
                if (servlet != null && servlet.trim().length() > 0)
                    res.setHeader("type", "servlet");
                if (error != null && error.trim().length() > 0)
                    res.setHeader("type", "500");
                if (io != null && io.trim().length() > 0)
                    res.setHeader("type", "io");
                res.setStatus(200);
                res.setContentLength(0);
                return;
            }


            res.setStatus(200);
            res.setContentLength(100);
            res.setContentType("application/octet-stream");

            res.flushBuffer();

            if (servlet != null && servlet.trim().length() > 0)
                throw new ServletException("Servlet Exception");

            if (io != null && io.trim().length() > 0)
                throw new IOException("IO Exception");

            if (error != null && error.trim().length() > 0)
                res.sendError(500, "servlet process was 500");
        }

    }
}

