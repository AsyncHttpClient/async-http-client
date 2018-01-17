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
package org.asynchttpclient;

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
import java.io.OutputStream;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.*;

public class AuthTimeoutTest extends AbstractBasicTest {

  private static final int REQUEST_TIMEOUT = 1000;
  private static final int SHORT_FUTURE_TIMEOUT = 500; // shorter than REQUEST_TIMEOUT
  private static final int LONG_FUTURE_TIMEOUT = 1500; // longer than REQUEST_TIMEOUT

  private Server server2;

  @BeforeClass(alwaysRun = true)
  @Override
  public void setUpGlobal() throws Exception {

    server = new Server();
    ServerConnector connector1 = addHttpConnector(server);
    addBasicAuthHandler(server, configureHandler());
    server.start();
    port1 = connector1.getLocalPort();

    server2 = new Server();
    ServerConnector connector2 = addHttpConnector(server2);
    addDigestAuthHandler(server2, configureHandler());
    server2.start();
    port2 = connector2.getLocalPort();

    logger.info("Local HTTP server started successfully");
  }

  @AfterClass(alwaysRun = true)
  public void tearDownGlobal() throws Exception {
    super.tearDownGlobal();
    server2.stop();
  }

  @Test(expectedExceptions = TimeoutException.class)
  public void basicAuthTimeoutTest() throws Throwable {
    try (AsyncHttpClient client = newClient()) {
      execute(client, true, false).get(LONG_FUTURE_TIMEOUT, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      throw e.getCause();
    }
  }

  @Test(expectedExceptions = TimeoutException.class)
  public void basicPreemptiveAuthTimeoutTest() throws Throwable {
    try (AsyncHttpClient client = newClient()) {
      execute(client, true, true).get(LONG_FUTURE_TIMEOUT, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      throw e.getCause();
    }
  }

  @Test(expectedExceptions = TimeoutException.class)
  public void digestAuthTimeoutTest() throws Throwable {
    try (AsyncHttpClient client = newClient()) {
      execute(client, false, false).get(LONG_FUTURE_TIMEOUT, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      throw e.getCause();
    }
  }

  @Test(expectedExceptions = TimeoutException.class, enabled = false)
  public void digestPreemptiveAuthTimeoutTest() throws Throwable {
    try (AsyncHttpClient client = newClient()) {
      execute(client, false, true).get(LONG_FUTURE_TIMEOUT, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      throw e.getCause();
    }
  }

  @Test(expectedExceptions = TimeoutException.class)
  public void basicAuthFutureTimeoutTest() throws Throwable {
    try (AsyncHttpClient client = newClient()) {
      execute(client, true, false).get(SHORT_FUTURE_TIMEOUT, TimeUnit.MILLISECONDS);
    }
  }

  @Test(expectedExceptions = TimeoutException.class)
  public void basicPreemptiveAuthFutureTimeoutTest() throws Throwable {
    try (AsyncHttpClient client = newClient()) {
      execute(client, true, true).get(SHORT_FUTURE_TIMEOUT, TimeUnit.MILLISECONDS);
    }
  }

  @Test(expectedExceptions = TimeoutException.class)
  public void digestAuthFutureTimeoutTest() throws Throwable {
    try (AsyncHttpClient client = newClient()) {
      execute(client, false, false).get(SHORT_FUTURE_TIMEOUT, TimeUnit.MILLISECONDS);
    }
  }

  @Test(expectedExceptions = TimeoutException.class, enabled = false)
  public void digestPreemptiveAuthFutureTimeoutTest() throws Throwable {
    try (AsyncHttpClient client = newClient()) {
      execute(client, false, true).get(SHORT_FUTURE_TIMEOUT, TimeUnit.MILLISECONDS);
    }
  }

  private AsyncHttpClient newClient() {
    return asyncHttpClient(config().setRequestTimeout(REQUEST_TIMEOUT));
  }

  protected Future<Response> execute(AsyncHttpClient client, boolean basic, boolean preemptive) {
    Realm.Builder realm;
    String url;

    if (basic) {
      realm = basicAuthRealm(USER, ADMIN);
      url = getTargetUrl();
    } else {
      realm = digestAuthRealm(USER, ADMIN);
      url = getTargetUrl2();
      if (preemptive) {
        realm.setRealmName("MyRealm");
        realm.setAlgorithm("MD5");
        realm.setQop("auth");
        realm.setNonce("fFDVc60re9zt8fFDvht0tNrYuvqrcchN");
      }
    }

    return client.prepareGet(url).setRealm(realm.setUsePreemptiveAuth(preemptive).build()).execute();
  }

  @Override
  protected String getTargetUrl() {
    return "http://localhost:" + port1 + "/";
  }

  @Override
  protected String getTargetUrl2() {
    return "http://localhost:" + port2 + "/";
  }

  @Override
  public AbstractHandler configureHandler() throws Exception {
    return new IncompleteResponseHandler();
  }

  private class IncompleteResponseHandler extends AbstractHandler {

    public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      // NOTE: handler sends less bytes than are given in Content-Length, which should lead to timeout
      response.setStatus(200);
      OutputStream out = response.getOutputStream();
      response.setIntHeader(CONTENT_LENGTH.toString(), 1000);
      out.write(0);
      out.flush();
      try {
        Thread.sleep(LONG_FUTURE_TIMEOUT + 100);
      } catch (InterruptedException e) {
        //
      }
    }
  }
}
