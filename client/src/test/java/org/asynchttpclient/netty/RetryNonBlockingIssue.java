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
package org.asynchttpclient.netty;

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.*;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
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

import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.testng.Assert.assertEquals;

//FIXME there's no retry actually
public class RetryNonBlockingIssue extends AbstractBasicTest {

  @BeforeClass(alwaysRun = true)
  public void setUpGlobal() throws Exception {
    server = new Server();
    ServerConnector connector = addHttpConnector(server);

    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    context.addServlet(new ServletHolder(new MockExceptionServlet()), "/*");
    server.setHandler(context);

    server.start();
    port1 = connector.getLocalPort();
  }

  protected String getTargetUrl() {
    return String.format("http://localhost:%d/", port1);
  }

  private ListenableFuture<Response> testMethodRequest(AsyncHttpClient client, int requests, String action, String id) {
    RequestBuilder r = get(getTargetUrl())
            .addQueryParam(action, "1")
            .addQueryParam("maxRequests", "" + requests)
            .addQueryParam("id", id);
    return client.executeRequest(r);
  }

  @Test
  public void testRetryNonBlocking() throws IOException, InterruptedException, ExecutionException {

    AsyncHttpClientConfig config = config()
            .setKeepAlive(true)
            .setMaxConnections(100)
            .setConnectTimeout(60000)
            .setRequestTimeout(30000)
            .build();

    try (AsyncHttpClient client = asyncHttpClient(config)) {
      List<ListenableFuture<Response>> res = new ArrayList<>();
      for (int i = 0; i < 32; i++) {
        res.add(testMethodRequest(client, 3, "servlet", UUID.randomUUID().toString()));
      }

      StringBuilder b = new StringBuilder();
      for (ListenableFuture<Response> r : res) {
        Response theres = r.get();
        assertEquals(200, theres.getStatusCode());
        b.append("==============\r\n")
                .append("Response Headers\r\n");
        HttpHeaders heads = theres.getHeaders();
        b.append(heads).append("\r\n")
                .append("==============\r\n");
      }
      System.out.println(b.toString());
      System.out.flush();
    }
  }

  @Test
  public void testRetryNonBlockingAsyncConnect() throws IOException, InterruptedException, ExecutionException {

    AsyncHttpClientConfig config = config()
            .setKeepAlive(true)
            .setMaxConnections(100)
            .setConnectTimeout(60000)
            .setRequestTimeout(30000)
            .build();

    try (AsyncHttpClient client = asyncHttpClient(config)) {
      List<ListenableFuture<Response>> res = new ArrayList<>();
      for (int i = 0; i < 32; i++) {
        res.add(testMethodRequest(client, 3, "servlet", UUID.randomUUID().toString()));
      }

      StringBuilder b = new StringBuilder();
      for (ListenableFuture<Response> r : res) {
        Response theres = r.get();
        assertEquals(theres.getStatusCode(), 200);
        b.append("==============\r\n")
                .append("Response Headers\r\n");
        HttpHeaders heads = theres.getHeaders();
        b.append(heads).append("\r\n")
                .append("==============\r\n");
      }
      System.out.println(b.toString());
      System.out.flush();
    }
  }

  @SuppressWarnings("serial")
  public class MockExceptionServlet extends HttpServlet {

    private Map<String, Integer> requests = new ConcurrentHashMap<>();

    private synchronized int increment(String id) {
      int val;
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
      int max;
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
