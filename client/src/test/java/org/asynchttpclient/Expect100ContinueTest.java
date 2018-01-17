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

import io.netty.handler.codec.http.HttpHeaderValues;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpHeaderNames.EXPECT;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE_STRING;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Test the Expect: 100-Continue.
 */
public class Expect100ContinueTest extends AbstractBasicTest {

  @Override
  public AbstractHandler configureHandler() throws Exception {
    return new ZeroCopyHandler();
  }

  @Test
  public void Expect100Continue() throws Exception {
    try (AsyncHttpClient client = asyncHttpClient()) {
      Future<Response> f = client.preparePut("http://localhost:" + port1 + "/")//
              .setHeader(EXPECT, HttpHeaderValues.CONTINUE)//
              .setBody(SIMPLE_TEXT_FILE)//
              .execute();
      Response resp = f.get();
      assertNotNull(resp);
      assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
      assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
    }
  }

  private static class ZeroCopyHandler extends AbstractHandler {
    public void handle(String s, Request r, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

      int size = 10 * 1024;
      if (httpRequest.getContentLength() > 0) {
        size = httpRequest.getContentLength();
      }
      byte[] bytes = new byte[size];
      if (bytes.length > 0) {
        final int read = httpRequest.getInputStream().read(bytes);
        httpResponse.getOutputStream().write(bytes, 0, read);
      }

      httpResponse.setStatus(200);
      httpResponse.getOutputStream().flush();
    }
  }
}
