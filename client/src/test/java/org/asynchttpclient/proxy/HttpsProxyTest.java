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

import org.asynchttpclient.*;
import org.asynchttpclient.request.body.generator.ByteArrayBodyGenerator;
import org.asynchttpclient.test.EchoHandler;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
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

  private Server server2;

  public AbstractHandler configureHandler() throws Exception {
    return new ConnectHandler();
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
  public void testRequestProxy() throws Exception {

    try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config().setFollowRedirect(true).setUseInsecureTrustManager(true))) {
      RequestBuilder rb = get(getTargetUrl2()).setProxyServer(proxyServer("localhost", port1));
      Response r = asyncHttpClient.executeRequest(rb.build()).get();
      assertEquals(r.getStatusCode(), 200);
    }
  }

  @Test
  public void testConfigProxy() throws Exception {
    AsyncHttpClientConfig config = config()
            .setFollowRedirect(true)
            .setProxyServer(proxyServer("localhost", port1).build())
            .setUseInsecureTrustManager(true)
            .build();
    try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config)) {
      Response r = asyncHttpClient.executeRequest(get(getTargetUrl2())).get();
      assertEquals(r.getStatusCode(), 200);
    }
  }

  @Test
  public void testNoDirectRequestBodyWithProxy() throws Exception {
    AsyncHttpClientConfig config = config()
      .setFollowRedirect(true)
      .setProxyServer(proxyServer("localhost", port1).build())
      .setUseInsecureTrustManager(true)
      .build();
    try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config)) {
      Response r = asyncHttpClient.executeRequest(post(getTargetUrl2()).setBody(new ByteArrayBodyGenerator(LARGE_IMAGE_BYTES))).get();
      assertEquals(r.getStatusCode(), 200);
    }
  }

  @Test
  public void testDecompressBodyWithProxy() throws Exception {
    AsyncHttpClientConfig config = config()
      .setFollowRedirect(true)
      .setProxyServer(proxyServer("localhost", port1).build())
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

  @Test
  public void testPooledConnectionsWithProxy() throws Exception {

    try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config().setFollowRedirect(true).setUseInsecureTrustManager(true).setKeepAlive(true))) {
      RequestBuilder rb = get(getTargetUrl2()).setProxyServer(proxyServer("localhost", port1));

      Response r1 = asyncHttpClient.executeRequest(rb.build()).get();
      assertEquals(r1.getStatusCode(), 200);

      Response r2 = asyncHttpClient.executeRequest(rb.build()).get();
      assertEquals(r2.getStatusCode(), 200);
    }
  }
}
