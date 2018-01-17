/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package org.asynchttpclient;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Tests to reproduce issues with handling of error responses
 *
 * @author Tatu Saloranta
 */
public class ErrorResponseTest extends AbstractBasicTest {
  final static String BAD_REQUEST_STR = "Very Bad Request! No cookies.";

  @Override
  public AbstractHandler configureHandler() throws Exception {
    return new ErrorHandler();
  }

  @Test(groups = "standalone")
  public void testQueryParameters() throws Exception {
    try (AsyncHttpClient client = asyncHttpClient()) {
      Future<Response> f = client.prepareGet("http://localhost:" + port1 + "/foo").addHeader("Accepts", "*/*").execute();
      Response resp = f.get(3, TimeUnit.SECONDS);
      assertNotNull(resp);
      assertEquals(resp.getStatusCode(), 400);
      assertEquals(resp.getResponseBody(), BAD_REQUEST_STR);
    }
  }

  private static class ErrorHandler extends AbstractHandler {
    public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      try {
        Thread.sleep(210L);
      } catch (InterruptedException e) {
        //
      }
      response.setContentType("text/plain");
      response.setStatus(400);
      OutputStream out = response.getOutputStream();
      out.write(BAD_REQUEST_STR.getBytes(UTF_8));
      out.flush();
    }
  }
}
