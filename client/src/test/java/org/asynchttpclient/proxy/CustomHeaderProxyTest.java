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
package org.asynchttpclient.proxy;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.asynchttpclient.request.body.generator.ByteArrayBodyGenerator;
import org.asynchttpclient.test.EchoHandler;
import org.asynchttpclient.util.HttpConstants;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.*;
import static org.testng.Assert.assertEquals;

/**
 * Proxy usage tests.
 */
public class CustomHeaderProxyTest extends AbstractBasicTest {

    private Server server2;

    private final String customHeaderName = "Custom-Header";
    private final String customHeaderValue = "Custom-Value";

    public AbstractHandler configureHandler() throws Exception {
      return new ProxyHandler(customHeaderName, customHeaderValue);
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
      server = new Server();
      ServerConnector connector = addHttpConnector(server);
      server.setHandler(configureHandler());
      server.start();
      port1 = connector.getLocalPort();

      server2 = new Server();
      ServerConnector connector2 = addHttpsConnector(server2);
      server2.setHandler(new EchoHandler());
      server2.start();
      port2 = connector2.getLocalPort();

      logger.info("Local HTTP server started successfully");
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
      server.stop();
      server2.stop();
    }

    @Test
    public void testHttpProxy() throws Exception {
      AsyncHttpClientConfig config = config()
        .setFollowRedirect(true)
        .setProxyServer(
          proxyServer("localhost", port1)
            .setCustomHeaders((req) -> new DefaultHttpHeaders().add(customHeaderName, customHeaderValue))
            .build()
        )
        .setUseInsecureTrustManager(true)
        .build();
      try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config)) {
        Response r = asyncHttpClient.executeRequest(post(getTargetUrl2()).setBody(new ByteArrayBodyGenerator(LARGE_IMAGE_BYTES))).get();
        assertEquals(r.getStatusCode(), 200);
      }
    }

    public static class ProxyHandler extends ConnectHandler {
      String customHeaderName;
      String customHeaderValue;

      public ProxyHandler(String customHeaderName, String customHeaderValue) {
        this.customHeaderName = customHeaderName;
        this.customHeaderValue = customHeaderValue;
      }

      @Override
      public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (HttpConstants.Methods.CONNECT.equalsIgnoreCase(request.getMethod())) {
          if (request.getHeader(customHeaderName).equals(customHeaderValue)) {
            response.setStatus(HttpServletResponse.SC_OK);
            super.handle(s, r, request, response);
          } else {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            r.setHandled(true);
          }
        } else {
          super.handle(s, r, request, response);
        }
      }
    }
}
