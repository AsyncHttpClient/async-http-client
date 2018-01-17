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
package org.asynchttpclient.request.body;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class InputStreamTest extends AbstractBasicTest {

  @Override
  public AbstractHandler configureHandler() throws Exception {
    return new InputStreamHandler();
  }

  @Test
  public void testInvalidInputStream() throws IOException, ExecutionException, InterruptedException {

    try (AsyncHttpClient c = asyncHttpClient()) {
      HttpHeaders h = new DefaultHttpHeaders().add(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);

      InputStream is = new InputStream() {

        int readAllowed;

        @Override
        public int available() {
          return 1; // Fake
        }

        @Override
        public int read() {
          int fakeCount = readAllowed++;
          if (fakeCount == 0) {
            return (int) 'a';
          } else if (fakeCount == 1) {
            return (int) 'b';
          } else if (fakeCount == 2) {
            return (int) 'c';
          } else {
            return -1;
          }
        }
      };

      Response resp = c.preparePost(getTargetUrl()).setHeaders(h).setBody(is).execute().get();
      assertNotNull(resp);
      assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
      assertEquals(resp.getHeader("X-Param"), "abc");
    }
  }

  private static class InputStreamHandler extends AbstractHandler {
    public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      if ("POST".equalsIgnoreCase(request.getMethod())) {
        byte[] bytes = new byte[3];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int read = 0;
        while (read > -1) {
          read = request.getInputStream().read(bytes);
          if (read > 0) {
            bos.write(bytes, 0, read);
          }
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.addHeader("X-Param", new String(bos.toByteArray()));
      } else { // this handler is to handle POST request
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
      }
      response.getOutputStream().flush();
      response.getOutputStream().close();
    }
  }
}
