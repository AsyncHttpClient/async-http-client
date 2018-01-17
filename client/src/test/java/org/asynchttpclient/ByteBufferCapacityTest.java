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
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.test.TestUtils.createTempFile;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class ByteBufferCapacityTest extends AbstractBasicTest {

  @Override
  public AbstractHandler configureHandler() throws Exception {
    return new BasicHandler();
  }

  @Test
  public void basicByteBufferTest() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      File largeFile = createTempFile(1024 * 100 * 10);
      final AtomicInteger byteReceived = new AtomicInteger();

      Response response = c.preparePut(getTargetUrl()).setBody(largeFile).execute(new AsyncCompletionHandlerAdapter() {
        @Override
        public State onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
          byteReceived.addAndGet(content.getBodyByteBuffer().capacity());
          return super.onBodyPartReceived(content);
        }

      }).get();

      assertNotNull(response);
      assertEquals(response.getStatusCode(), 200);
      assertEquals(byteReceived.get(), largeFile.length());
      assertEquals(response.getResponseBody().length(), largeFile.length());
    }
  }

  public String getTargetUrl() {
    return String.format("http://localhost:%d/foo/test", port1);
  }

  private class BasicHandler extends AbstractHandler {

    public void handle(String s, Request r, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

      Enumeration<?> e = httpRequest.getHeaderNames();
      String param;
      while (e.hasMoreElements()) {
        param = e.nextElement().toString();
        httpResponse.addHeader("X-" + param, httpRequest.getHeader(param));
      }

      int size = 10 * 1024;
      if (httpRequest.getContentLength() > 0) {
        size = httpRequest.getContentLength();
      }
      byte[] bytes = new byte[size];
      if (bytes.length > 0) {
        final InputStream in = httpRequest.getInputStream();
        final OutputStream out = httpResponse.getOutputStream();
        int read;
        while ((read = in.read(bytes)) != -1) {
          out.write(bytes, 0, read);
        }
      }

      httpResponse.setStatus(200);
      httpResponse.getOutputStream().flush();
      httpResponse.getOutputStream().close();
    }
  }
}
