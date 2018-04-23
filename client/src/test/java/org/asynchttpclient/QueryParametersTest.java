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
 */
package org.asynchttpclient;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Testing query parameters support.
 *
 * @author Hubert Iwaniuk
 */
public class QueryParametersTest extends AbstractBasicTest {
  @Override
  public AbstractHandler configureHandler() throws Exception {
    return new QueryStringHandler();
  }

  @Test
  public void testQueryParameters() throws IOException, ExecutionException, TimeoutException, InterruptedException {
    try (AsyncHttpClient client = asyncHttpClient()) {
      Future<Response> f = client.prepareGet("http://localhost:" + port1).addQueryParam("a", "1").addQueryParam("b", "2").execute();
      Response resp = f.get(3, TimeUnit.SECONDS);
      assertNotNull(resp);
      assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
      assertEquals(resp.getHeader("a"), "1");
      assertEquals(resp.getHeader("b"), "2");
    }
  }

  @Test
  public void testUrlRequestParametersEncoding() throws IOException, ExecutionException, InterruptedException {
    String URL = getTargetUrl() + "?q=";
    String REQUEST_PARAM = "github github \ngithub";

    try (AsyncHttpClient client = asyncHttpClient()) {
      String requestUrl2 = URL + URLEncoder.encode(REQUEST_PARAM, UTF_8.name());
      logger.info("Executing request [{}] ...", requestUrl2);
      Response response = client.prepareGet(requestUrl2).execute().get();
      String s = URLDecoder.decode(response.getHeader("q"), UTF_8.name());
      assertEquals(s, REQUEST_PARAM);
    }
  }

  @Test
  public void urlWithColonTest() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      String query = "test:colon:";
      Response response = c.prepareGet(String.format("http://localhost:%d/foo/test/colon?q=%s", port1, query)).setHeader("Content-Type", "text/html").execute().get(TIMEOUT, TimeUnit.SECONDS);

      assertEquals(response.getHeader("q"), query);
    }
  }

  private class QueryStringHandler extends AbstractHandler {
    public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      if ("GET".equalsIgnoreCase(request.getMethod())) {
        String qs = request.getQueryString();
        if (isNonEmpty(qs)) {
          for (String qnv : qs.split("&")) {
            String nv[] = qnv.split("=");
            response.addHeader(nv[0], nv[1]);
          }
          response.setStatus(HttpServletResponse.SC_OK);
        } else {
          response.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE);
        }
      } else { // this handler is to handle POST request
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
      }
      r.setHandled(true);
    }
  }
}
