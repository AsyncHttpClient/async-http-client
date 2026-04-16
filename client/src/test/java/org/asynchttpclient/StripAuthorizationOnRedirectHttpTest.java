/*
 *    Copyright (c) 2015-2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class StripAuthorizationOnRedirectHttpTest {
  private static HttpServer server;
  private static int port;
  private static volatile String lastAuthHeader;

  @BeforeClass
  public static void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.createContext("/redirect", new RedirectHandler());
    server.createContext("/final", new FinalHandler());
    server.start();
  }

  @AfterClass
  public static void stopServer() {
    server.stop(0);
  }

  static class RedirectHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) {
      String auth = exchange.getRequestHeaders().getFirst("Authorization");
      lastAuthHeader = auth;
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/final");
      try {
        exchange.sendResponseHeaders(302, -1);
      } catch (Exception ignored) {
      }
      exchange.close();
    }
  }

  static class FinalHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) {
      String auth = exchange.getRequestHeaders().getFirst("Authorization");
      lastAuthHeader = auth;
      try {
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().close();
      } catch (Exception ignored) {
      }
      exchange.close();
    }
  }

  @Test
  public void testAuthHeaderPropagatedByDefault() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      lastAuthHeader = null;
      client.prepareGet("http://127.0.0.1:" + port + "/redirect")
              .setHeader("Authorization", "Bearer testtoken")
              .execute()
              .get(5, TimeUnit.SECONDS);
      // Same-origin (same host:port): default should preserve Authorization on /final
      assertEquals(lastAuthHeader, "Bearer testtoken",
              "Authorization header should be present on same-origin redirect by default");
    }
  }

  @Test
  public void testAuthHeaderStrippedWhenEnabled() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .setStripAuthorizationOnRedirect(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      lastAuthHeader = null;
      client.prepareGet("http://127.0.0.1:" + port + "/redirect")
              .setHeader("Authorization", "Bearer testtoken")
              .execute()
              .get(5, TimeUnit.SECONDS);
      // When enabled, Authorization header must be stripped even on same-origin redirects
      assertNull(lastAuthHeader, "Authorization header should be stripped on redirect when enabled");
    }
  }
}
