/*
 * Copyright (c) 2016 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient;

import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.test.EchoHandler;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHENTICATE;
import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;

/**
 * Test that validates that when having an HTTP proxy and trying to access an HTTP through the proxy the proxy credentials should be passed after it gets a 407 response.
 */
public class BasicHttpProxyToHttpTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(BasicHttpProxyToHttpTest.class);

  private int httpPort;
  private int proxyPort;

  private Server httpServer;
  private Server proxy;

  @BeforeClass
  public void setUpGlobal() throws Exception {

    httpServer = new Server();
    ServerConnector connector1 = addHttpConnector(httpServer);
    httpServer.setHandler(new EchoHandler());
    httpServer.start();
    httpPort = connector1.getLocalPort();

    proxy = new Server();
    ServerConnector connector2 = addHttpConnector(proxy);
    ServletHandler servletHandler = new ServletHandler();
    ServletHolder servletHolder = servletHandler.addServletWithMapping(BasicAuthProxyServlet.class, "/*");
    servletHolder.setInitParameter("maxThreads", "20");
    proxy.setHandler(servletHandler);
    proxy.start();
    proxyPort = connector2.getLocalPort();

    LOGGER.info("Local HTTP Server (" + httpPort + "), Proxy (" + proxyPort + ") started successfully");
  }

  @AfterClass(alwaysRun = true)
  public void tearDownGlobal() {
    if (proxy != null) {
      try {
        proxy.stop();
      } catch (Exception e) {
        LOGGER.error("Failed to properly close proxy", e);
      }
    }
    if (httpServer != null) {
      try {
        httpServer.stop();
      } catch (Exception e) {
        LOGGER.error("Failed to properly close server", e);
      }
    }
  }

  @Test
  public void nonPreemptiveProxyAuthWithPlainHttpTarget() throws IOException, InterruptedException, ExecutionException {
    try (AsyncHttpClient client = asyncHttpClient()) {
      String targetUrl = "http://localhost:" + httpPort + "/foo/bar";
      Request request = get(targetUrl)//
              .setProxyServer(proxyServer("127.0.0.1", proxyPort).setRealm(realm(AuthScheme.BASIC, "johndoe", "pass")))//
              // .setRealm(realm(AuthScheme.BASIC, "user", "passwd"))//
              .build();
      Future<Response> responseFuture = client.executeRequest(request);
      Response response = responseFuture.get();

      Assert.assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
      Assert.assertEquals("/foo/bar", response.getHeader("X-pathInfo"));
    }
  }

  @SuppressWarnings("serial")
  public static class BasicAuthProxyServlet extends ProxyServlet {

    @Override
    protected void service(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {
      LOGGER.debug(">>> got a request !");

      String authorization = request.getHeader(PROXY_AUTHORIZATION.toString());
      if (authorization == null) {
        response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
        response.setHeader(PROXY_AUTHENTICATE.toString(), "Basic realm=\"Fake Realm\"");
        response.getOutputStream().flush();

      } else if (authorization.equals("Basic am9obmRvZTpwYXNz")) {
        super.service(request, response);

      } else {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getOutputStream().flush();
      }
    }
  }
}