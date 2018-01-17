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

import org.asynchttpclient.exception.RemotelyClosedException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class RetryRequestTest extends AbstractBasicTest {
  protected String getTargetUrl() {
    return String.format("http://localhost:%d/", port1);
  }

  @Override
  public AbstractHandler configureHandler() throws Exception {
    return new SlowAndBigHandler();
  }

  @Test
  public void testMaxRetry() {
    try (AsyncHttpClient ahc = asyncHttpClient(config().setMaxRequestRetry(0))) {
      ahc.executeRequest(ahc.prepareGet(getTargetUrl()).build()).get();
      fail();
    } catch (Exception t) {
      assertEquals(t.getCause(), RemotelyClosedException.INSTANCE);
    }
  }

  public static class SlowAndBigHandler extends AbstractHandler {

    public void handle(String pathInContext, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

      int load = 100;
      httpResponse.setStatus(200);
      httpResponse.setContentLength(load);
      httpResponse.setContentType("application/octet-stream");

      httpResponse.flushBuffer();

      OutputStream os = httpResponse.getOutputStream();
      for (int i = 0; i < load; i++) {
        os.write(i % 255);

        try {
          Thread.sleep(300);
        } catch (InterruptedException ex) {
          // nuku
        }

        if (i > load / 10) {
          httpResponse.sendError(500);
        }
      }

      httpResponse.getOutputStream().flush();
      httpResponse.getOutputStream().close();
    }
  }
}
