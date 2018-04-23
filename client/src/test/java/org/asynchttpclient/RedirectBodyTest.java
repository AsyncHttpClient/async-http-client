/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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

import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.LOCATION;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class RedirectBodyTest extends AbstractBasicTest {

  private volatile boolean redirectAlreadyPerformed;
  private volatile String receivedContentType;

  @BeforeMethod
  public void setUp() {
    redirectAlreadyPerformed = false;
    receivedContentType = null;
  }

  @Override
  public AbstractHandler configureHandler() throws Exception {
    return new AbstractHandler() {
      @Override
      public void handle(String pathInContext, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {

        String redirectHeader = httpRequest.getHeader("X-REDIRECT");
        if (redirectHeader != null && !redirectAlreadyPerformed) {
          redirectAlreadyPerformed = true;
          httpResponse.setStatus(Integer.valueOf(redirectHeader));
          httpResponse.setContentLength(0);
          httpResponse.setHeader(LOCATION.toString(), getTargetUrl());

        } else {
          receivedContentType = request.getContentType();
          httpResponse.setStatus(200);
          int len = request.getContentLength();
          httpResponse.setContentLength(len);
          if (len > 0) {
            byte[] buffer = new byte[len];
            IOUtils.read(request.getInputStream(), buffer);
            httpResponse.getOutputStream().write(buffer);
          }
        }
        httpResponse.getOutputStream().flush();
        httpResponse.getOutputStream().close();
      }
    };
  }

  @Test
  public void regular301LosesBody() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
      String body = "hello there";
      String contentType = "text/plain; charset=UTF-8";

      Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "301").execute().get(TIMEOUT, TimeUnit.SECONDS);
      assertEquals(response.getResponseBody(), "");
      assertNull(receivedContentType);
    }
  }

  @Test
  public void regular302LosesBody() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
      String body = "hello there";
      String contentType = "text/plain; charset=UTF-8";

      Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "302").execute().get(TIMEOUT, TimeUnit.SECONDS);
      assertEquals(response.getResponseBody(), "");
      assertNull(receivedContentType);
    }
  }

  @Test
  public void regular302StrictKeepsBody() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true).setStrict302Handling(true))) {
      String body = "hello there";
      String contentType = "text/plain; charset=UTF-8";

      Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "302").execute().get(TIMEOUT, TimeUnit.SECONDS);
      assertEquals(response.getResponseBody(), body);
      assertEquals(receivedContentType, contentType);
    }
  }

  @Test
  public void regular307KeepsBody() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
      String body = "hello there";
      String contentType = "text/plain; charset=UTF-8";

      Response response = c.preparePost(getTargetUrl()).setHeader(CONTENT_TYPE, contentType).setBody(body).setHeader("X-REDIRECT", "307").execute().get(TIMEOUT, TimeUnit.SECONDS);
      assertEquals(response.getResponseBody(), body);
      assertEquals(receivedContentType, contentType);
    }
  }
}
