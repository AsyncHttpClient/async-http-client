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

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.testng.Assert.assertNull;

/**
 * Verifies that credentials are stripped when a redirect downgrades the scheme
 * from HTTPS to HTTP, even if host and port would otherwise match.
 */
public class HttpsDowngradeRedirectTest {

  private static Server server;
  private static int httpPort;
  private static int httpsPort;
  private static final AtomicReference<String> authOnHttpTarget = new AtomicReference<>();
  private static final AtomicReference<String> cookieOnHttpTarget = new AtomicReference<>();

  @BeforeClass(alwaysRun = true)
  public static void setUp() throws Exception {
    server = new Server();
    ServerConnector httpConnector = addHttpConnector(server);
    ServerConnector httpsConnector = addHttpsConnector(server);

    server.setHandler(new AbstractHandler() {
      @Override
      public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) {
        if ("/redirect".equals(target)) {
          // Redirect from HTTPS to plain HTTP on the same host — a scheme downgrade.
          response.setStatus(302);
          response.setHeader("Location", "http://localhost:" + httpPort + "/target");
        } else if ("/target".equals(target)) {
          authOnHttpTarget.set(request.getHeader("Authorization"));
          cookieOnHttpTarget.set(request.getHeader("Cookie"));
          response.setStatus(200);
        }
        baseRequest.setHandled(true);
      }
    });

    server.start();
    httpPort = httpConnector.getLocalPort();
    httpsPort = httpsConnector.getLocalPort();
  }

  @AfterClass(alwaysRun = true)
  public static void tearDown() throws Exception {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void httpsToHttpDowngradeStripsAuthorization() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .setUseInsecureTrustManager(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      authOnHttpTarget.set(null);

      client.prepareGet("https://localhost:" + httpsPort + "/redirect")
              .setHeader("Authorization", "Bearer secret-token")
              .execute()
              .get(10, TimeUnit.SECONDS);

      // HTTPS -> HTTP is a scheme downgrade: credentials must not leak to the plain-HTTP target.
      assertNull(authOnHttpTarget.get(),
              "Authorization header must be stripped when redirect downgrades HTTPS to HTTP");
    }
  }

  /**
   * HTTPS-to-HTTP downgrade also strips the Cookie header. Regression test for GHSA-fmxf-pm6p-7xgm.
   */
  @Test
  public void httpsToHttpDowngradeStripsCookie() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .setUseInsecureTrustManager(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      cookieOnHttpTarget.set(null);

      client.prepareGet("https://localhost:" + httpsPort + "/redirect")
              .setHeader("Cookie", "session=secret-session")
              .execute()
              .get(10, TimeUnit.SECONDS);

      assertNull(cookieOnHttpTarget.get(),
              "Cookie header must be stripped when redirect downgrades HTTPS to HTTP");
    }
  }
}
