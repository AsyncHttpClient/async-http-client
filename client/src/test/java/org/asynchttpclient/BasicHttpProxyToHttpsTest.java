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
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.*;

/**
 * Test that validates that when having an HTTP proxy and trying to access an HTTPS
 * through the proxy the proxy credentials and a custom user-agent (if set) should be passed during the CONNECT request.
 */
public class BasicHttpProxyToHttpsTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(BasicHttpProxyToHttpsTest.class);
  private static final String CUSTOM_USER_AGENT = "custom-user-agent";

  private int httpPort;
  private int proxyPort;

  private Server httpServer;
  private Server proxy;

  @BeforeClass(alwaysRun = true)
  public void setUpGlobal() throws Exception {

    // HTTP server
    httpServer = new Server();
    ServerConnector connector1 = addHttpsConnector(httpServer);
    httpServer.setHandler(new EchoHandler());
    httpServer.start();
    httpPort = connector1.getLocalPort();

    // proxy
    proxy = new Server();
    ServerConnector connector2 = addHttpConnector(proxy);
    ConnectHandler connectHandler = new ConnectHandler() {

      @Override
      // This proxy receives a CONNECT request from the client before making the real request for the target host.
      protected boolean handleAuthentication(HttpServletRequest request, HttpServletResponse response, String address) {

        // If the userAgent of the CONNECT request is the same as the default userAgent,
        // then the custom userAgent was not properly propagated and the test should fail.
        String userAgent = request.getHeader(USER_AGENT.toString());
        if(userAgent.equals(defaultUserAgent())) {
          return false;
        }

        // If the authentication failed, the test should also fail.
        String authorization = request.getHeader(PROXY_AUTHORIZATION.toString());
        if (authorization == null) {
          response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
          response.setHeader(PROXY_AUTHENTICATE.toString(), "Basic realm=\"Fake Realm\"");
          return false;
        }
         else if (authorization.equals("Basic am9obmRvZTpwYXNz")) {
          return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
      }
    };
    proxy.setHandler(connectHandler);
    proxy.start();
    proxyPort = connector2.getLocalPort();

    LOGGER.info("Local HTTP Server (" + httpPort + "), Proxy (" + proxyPort + ") started successfully");
  }

  @AfterClass(alwaysRun = true)
  public void tearDownGlobal() throws Exception {
    httpServer.stop();
    proxy.stop();
  }

  @Test
  public void nonPreemptiveProxyAuthWithHttpsTarget() throws IOException, InterruptedException, ExecutionException {
    try (AsyncHttpClient client = asyncHttpClient(config().setUseInsecureTrustManager(true))) {
      String targetUrl = "https://localhost:" + httpPort + "/foo/bar";
      Request request = get(targetUrl)
              .setProxyServer(proxyServer("127.0.0.1", proxyPort).setRealm(realm(AuthScheme.BASIC, "johndoe", "pass")))
              .setHeader("user-agent", CUSTOM_USER_AGENT)
              // .setRealm(realm(AuthScheme.BASIC, "user", "passwd"))
              .build();
      Future<Response> responseFuture = client.executeRequest(request);
      Response response = responseFuture.get();

      Assert.assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
      Assert.assertEquals("/foo/bar", response.getHeader("X-pathInfo"));
    }
  }
}
