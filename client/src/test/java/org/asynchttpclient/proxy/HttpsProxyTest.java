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

import java.util.ArrayList;
import java.util.List;
import org.asynchttpclient.*;
import org.asynchttpclient.request.body.generator.ByteArrayBodyGenerator;
import org.asynchttpclient.test.EchoHandler;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_BYTES;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.testng.Assert.assertEquals;

/**
 * Proxy usage tests.
 */
public class HttpsProxyTest extends AbstractBasicTest {

  private List<Server> servers;
  private int httpsProxyPort;

  public AbstractHandler configureHandler() throws Exception {
    return new ConnectHandler();
  }
  
  @DataProvider (name = "serverPorts")
  public Object[][] serverPorts() {
    return new Object[][] {{port1, ProxyType.HTTP}, {httpsProxyPort, ProxyType.HTTPS}};
  }
  

  @BeforeClass(alwaysRun = true)
  public void setUpGlobal() throws Exception {
    servers = new ArrayList<>();
    port1 = startServer(configureHandler(), false);

    port2 = startServer(new EchoHandler(), true);

    httpsProxyPort = startServer(configureHandler(), true);

    logger.info("Local HTTP server started successfully");
  }
  
  private int startServer(Handler handler, boolean secure) throws Exception {
    Server server = new Server();
    @SuppressWarnings("resource")
    ServerConnector connector = secure ? addHttpsConnector(server) : addHttpConnector(server);
    server.setHandler(handler);
    server.start();
    servers.add(server);
    return connector.getLocalPort();
  }

  @AfterClass(alwaysRun = true)
  public void tearDownGlobal() {
    servers.forEach(t -> {
      try {
        t.stop();
      } catch (Exception e) {
        // couldn't stop server
      }
    });
  }

  @Test(dataProvider = "serverPorts")
  public void testRequestProxy(int proxyPort, ProxyType type) throws Exception {

    try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config().setFollowRedirect(true).setUseInsecureTrustManager(true))) {
      RequestBuilder rb = get(getTargetUrl2()).setProxyServer(proxyServer("localhost", proxyPort).setProxyType(type));
      Response r = asyncHttpClient.executeRequest(rb.build()).get();
      assertEquals(r.getStatusCode(), 200);
    }
  }

  @Test(dataProvider = "serverPorts")
  public void testConfigProxy(int proxyPort, ProxyType type) throws Exception {
    AsyncHttpClientConfig config = config()
            .setFollowRedirect(true)
            .setProxyServer(proxyServer("localhost", proxyPort).setProxyType(type).build())
            .setUseInsecureTrustManager(true)
            .build();
    try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config)) {
      Response r = asyncHttpClient.executeRequest(get(getTargetUrl2())).get();
      assertEquals(r.getStatusCode(), 200);
    }
  }

  @Test(dataProvider = "serverPorts")
  public void testNoDirectRequestBodyWithProxy(int proxyPort, ProxyType type) throws Exception {
    AsyncHttpClientConfig config = config()
      .setFollowRedirect(true)
      .setProxyServer(proxyServer("localhost", proxyPort).setProxyType(type).build())
      .setUseInsecureTrustManager(true)
      .build();
    try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config)) {
      Response r = asyncHttpClient.executeRequest(post(getTargetUrl2()).setBody(new ByteArrayBodyGenerator(LARGE_IMAGE_BYTES))).get();
      assertEquals(r.getStatusCode(), 200);
    }
  }

  @Test(dataProvider = "serverPorts")
  public void testDecompressBodyWithProxy(int proxyPort, ProxyType type) throws Exception {
    AsyncHttpClientConfig config = config()
      .setFollowRedirect(true)
      .setProxyServer(proxyServer("localhost", proxyPort).setProxyType(type).build())
      .setUseInsecureTrustManager(true)
      .build();
    try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config)) {
      String body = "hello world";
      Response r = asyncHttpClient.executeRequest(post(getTargetUrl2())
        .setHeader("X-COMPRESS", "true")
        .setBody(body)).get();
      assertEquals(r.getStatusCode(), 200);
      assertEquals(r.getResponseBody(), body);
    }
  }

  @Test(dataProvider = "serverPorts")
  public void testPooledConnectionsWithProxy(int proxyPort, ProxyType type) throws Exception {

    try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config().setFollowRedirect(true).setUseInsecureTrustManager(true).setKeepAlive(true))) {
      RequestBuilder rb = get(getTargetUrl2()).setProxyServer(proxyServer("localhost", proxyPort).setProxyType(type));

      Response r1 = asyncHttpClient.executeRequest(rb.build()).get();
      assertEquals(r1.getStatusCode(), 200);

      Response r2 = asyncHttpClient.executeRequest(rb.build()).get();
      assertEquals(r2.getStatusCode(), 200);
    }
  }
}
