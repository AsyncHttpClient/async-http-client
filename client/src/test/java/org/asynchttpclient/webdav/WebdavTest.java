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
package org.asynchttpclient.webdav;

import org.apache.catalina.Context;
import org.apache.catalina.servlets.WebdavServlet;
import org.apache.catalina.startup.Tomcat;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;

import static org.asynchttpclient.Dsl.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class WebdavTest {

  private Tomcat tomcat;
  private int port1;

  @SuppressWarnings("serial")
  @BeforeClass(alwaysRun = true)
  public void setUpGlobal() throws Exception {

    String path = new File(".").getAbsolutePath() + "/target";

    tomcat = new Tomcat();
    tomcat.setHostname("localhost");
    tomcat.setPort(0);
    tomcat.setBaseDir(path);
    Context ctx = tomcat.addContext("", path);

    Tomcat.addServlet(ctx, "webdav", new WebdavServlet() {
      @Override
      public void init(ServletConfig config) throws ServletException {

        super.init(new ServletConfig() {

          @Override
          public String getServletName() {
            return config.getServletName();
          }

          @Override
          public ServletContext getServletContext() {
            return config.getServletContext();
          }

          @Override
          public Enumeration<String> getInitParameterNames() {
            // FIXME
            return config.getInitParameterNames();
          }

          @Override
          public String getInitParameter(String name) {
            switch (name) {
              case "readonly":
                return "false";
              case "listings":
                return "true";
              default:
                return config.getInitParameter(name);
            }
          }
        });
      }

    });
    ctx.addServletMappingDecoded("/*", "webdav");
    tomcat.start();
    port1 = tomcat.getConnector().getLocalPort();
  }

  @AfterClass(alwaysRun = true)
  public void tearDownGlobal() throws Exception {
    tomcat.stop();
  }

  private String getTargetUrl() {
    return String.format("http://localhost:%s/folder1", port1);
  }

  @AfterMethod(alwaysRun = true)
  public void clean() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      c.executeRequest(delete(getTargetUrl())).get();
    }
  }

  @Test
  public void mkcolWebDavTest1() throws InterruptedException, IOException, ExecutionException {
    try (AsyncHttpClient c = asyncHttpClient()) {
      Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl()).build();
      Response response = c.executeRequest(mkcolRequest).get();
      assertEquals(response.getStatusCode(), 201);
    }
  }

  @Test
  public void mkcolWebDavTest2() throws InterruptedException, IOException, ExecutionException {
    try (AsyncHttpClient c = asyncHttpClient()) {
      Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl() + "/folder2").build();
      Response response = c.executeRequest(mkcolRequest).get();
      assertEquals(response.getStatusCode(), 409);
    }
  }

  @Test
  public void basicPropFindWebDavTest() throws InterruptedException, IOException, ExecutionException {
    try (AsyncHttpClient c = asyncHttpClient()) {
      Request propFindRequest = new RequestBuilder("PROPFIND").setUrl(getTargetUrl()).build();
      Response response = c.executeRequest(propFindRequest).get();

      assertEquals(response.getStatusCode(), 404);
    }
  }

  @Test
  public void propFindWebDavTest() throws InterruptedException, IOException, ExecutionException {
    try (AsyncHttpClient c = asyncHttpClient()) {
      Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl()).build();
      Response response = c.executeRequest(mkcolRequest).get();
      assertEquals(response.getStatusCode(), 201);

      Request putRequest = put(getTargetUrl() + "/Test.txt").setBody("this is a test").build();
      response = c.executeRequest(putRequest).get();
      assertEquals(response.getStatusCode(), 201);

      Request propFindRequest = new RequestBuilder("PROPFIND").setUrl(getTargetUrl() + "/Test.txt").build();
      response = c.executeRequest(propFindRequest).get();

      assertEquals(response.getStatusCode(), 207);
      assertTrue(response.getResponseBody().contains("HTTP/1.1 200 OK"), "Got " + response.getResponseBody());
    }
  }

  @Test
  public void propFindCompletionHandlerWebDavTest() throws InterruptedException, IOException, ExecutionException {
    try (AsyncHttpClient c = asyncHttpClient()) {
      Request mkcolRequest = new RequestBuilder("MKCOL").setUrl(getTargetUrl()).build();
      Response response = c.executeRequest(mkcolRequest).get();
      assertEquals(response.getStatusCode(), 201);

      Request propFindRequest = new RequestBuilder("PROPFIND").setUrl(getTargetUrl()).build();
      WebDavResponse webDavResponse = c.executeRequest(propFindRequest, new WebDavCompletionHandlerBase<WebDavResponse>() {
        /**
         * {@inheritDoc}
         */
        @Override
        public void onThrowable(Throwable t) {

          t.printStackTrace();
        }

        @Override
        public WebDavResponse onCompleted(WebDavResponse response) {
          return response;
        }
      }).get();

      assertEquals(webDavResponse.getStatusCode(), 207);
      assertTrue(webDavResponse.getResponseBody().contains("HTTP/1.1 200 OK"), "Got " + response.getResponseBody());
    }
  }
}
