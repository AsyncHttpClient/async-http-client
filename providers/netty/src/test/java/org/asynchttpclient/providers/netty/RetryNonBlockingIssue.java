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
package org.asynchttpclient.providers.netty;

import static org.asynchttpclient.async.util.TestUtils.findFreePort;
import static org.asynchttpclient.async.util.TestUtils.newJettyHttpServer;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.async.AbstractBasicTest;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

//FIXME there's no retry actually
public class RetryNonBlockingIssue extends AbstractBasicTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        port1 = findFreePort();
        server = newJettyHttpServer(port1);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new MockExceptionServlet()), "/*");

        server.setHandler(context);
        server.start();
    }

    protected String getTargetUrl() {
        return String.format("http://127.0.0.1:%d/", port1);
    }

    private ListenableFuture<Response> testMethodRequest(AsyncHttpClient client, int requests, String action, String id) throws IOException {
        Request r = new RequestBuilder("GET")//
                .setUrl(getTargetUrl())//
                .addQueryParam(action, "1")//
                .addQueryParam("maxRequests", "" + requests)//
                .addQueryParam("id", id)//
                .build();
        return client.executeRequest(r);
    }

    /**
     * Tests that a head request can be made
     * 
     * @throws IOException
     * @throws ExecutionException
     * @throws InterruptedException
     */
    @Test
    public void testRetryNonBlocking() throws IOException, InterruptedException, ExecutionException {

        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder()//
                .setAllowPoolingConnections(true)//
                .setMaxConnections(100)//
                .setConnectionTimeout(60000)//
                .setRequestTimeout(30000)//
                .build();

        AsyncHttpClient client = getAsyncHttpClient(config);
        try {
            List<ListenableFuture<Response>> res = new ArrayList<ListenableFuture<Response>>();
            for (int i = 0; i < 32; i++) {
                res.add(testMethodRequest(client, 3, "servlet", UUID.randomUUID().toString()));
            }

            StringBuilder b = new StringBuilder();
            for (ListenableFuture<Response> r : res) {
                Response theres = r.get();
                assertEquals(200, theres.getStatusCode());
                b.append("==============\r\n");
                b.append("Response Headers\r\n");
                Map<String, List<String>> heads = theres.getHeaders();
                b.append(heads + "\r\n");
                b.append("==============\r\n");
                assertTrue(heads.size() > 0);
            }
            System.out.println(b.toString());
            System.out.flush();

        } finally {
            client.close();
        }
    }

    @Test
    public void testRetryNonBlockingAsyncConnect() throws IOException, InterruptedException, ExecutionException {

        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder()//
                .setAllowPoolingConnections(true)//
                .setMaxConnections(100)//
                .setConnectionTimeout(60000)//
                .setRequestTimeout(30000)//
                .build();

        AsyncHttpClient client = getAsyncHttpClient(config);

        try {
            List<ListenableFuture<Response>> res = new ArrayList<ListenableFuture<Response>>();
            for (int i = 0; i < 32; i++) {
                res.add(testMethodRequest(client, 3, "servlet", UUID.randomUUID().toString()));
            }

            StringBuilder b = new StringBuilder();
            for (ListenableFuture<Response> r : res) {
                Response theres = r.get();
                assertEquals(theres.getStatusCode(), 200);
                b.append("==============\r\n");
                b.append("Response Headers\r\n");
                Map<String, List<String>> heads = theres.getHeaders();
                b.append(heads + "\r\n");
                b.append("==============\r\n");
                assertTrue(heads.size() > 0);
            }
            System.out.println(b.toString());
            System.out.flush();

        } finally {
            client.close();
        }
    }

    @SuppressWarnings("serial")
    public class MockExceptionServlet extends HttpServlet {

        private Map<String, Integer> requests = new ConcurrentHashMap<String, Integer>();

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

        public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
            String maxRequests = req.getParameter("maxRequests");
            int max = 0;
            try {
                max = Integer.parseInt(maxRequests);
            } catch (NumberFormatException e) {
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
                res.flushBuffer();
                return;
            }

            res.setStatus(200);
            res.setContentLength(100);
            res.setContentType("application/octet-stream");
            res.flushBuffer();

            // error after flushing the status
            if (servlet != null && servlet.trim().length() > 0)
                throw new ServletException("Servlet Exception");

            if (io != null && io.trim().length() > 0)
                throw new IOException("IO Exception");

            if (error != null && error.trim().length() > 0) {
                res.sendError(500, "servlet process was 500");
            }
        }
    }
}
