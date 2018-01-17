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
package org.asynchttpclient.request.body;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.createTempFile;
import static org.testng.Assert.assertEquals;

public class PutFileTest extends AbstractBasicTest {

  private void put(int fileSize) throws Exception {
    File file = createTempFile(fileSize);
    try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(2000))) {
      Response response = client.preparePut(getTargetUrl()).setBody(file).execute().get();
      assertEquals(response.getStatusCode(), 200);
    }
  }

  @Test
  public void testPutLargeFile() throws Exception {
    put(1024 * 1024);
  }

  @Test
  public void testPutSmallFile() throws Exception {
    put(1024);
  }

  @Override
  public AbstractHandler configureHandler() throws Exception {
    return new AbstractHandler() {

      public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {

        InputStream is = baseRequest.getInputStream();
        int read;
        do {
          // drain upload
          read = is.read();
        } while (read >= 0);

        response.setStatus(200);
        response.getOutputStream().flush();
        response.getOutputStream().close();
        baseRequest.setHandled(true);
      }
    };
  }
}
