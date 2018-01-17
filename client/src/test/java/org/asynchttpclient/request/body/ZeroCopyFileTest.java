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

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.*;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE_STRING;
import static org.testng.Assert.*;

/**
 * Zero copy test which use FileChannel.transfer under the hood . The same SSL test is also covered in {@link BasicHttpsTest}
 */
public class ZeroCopyFileTest extends AbstractBasicTest {

  @Test
  public void zeroCopyPostTest() throws IOException, ExecutionException, InterruptedException {
    try (AsyncHttpClient client = asyncHttpClient()) {
      final AtomicBoolean headerSent = new AtomicBoolean(false);
      final AtomicBoolean operationCompleted = new AtomicBoolean(false);

      Response resp = client.preparePost("http://localhost:" + port1 + "/").setBody(SIMPLE_TEXT_FILE).execute(new AsyncCompletionHandler<Response>() {

        public State onHeadersWritten() {
          headerSent.set(true);
          return State.CONTINUE;
        }

        public State onContentWritten() {
          operationCompleted.set(true);
          return State.CONTINUE;
        }

        @Override
        public Response onCompleted(Response response) {
          return response;
        }
      }).get();
      assertNotNull(resp);
      assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
      assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
      assertTrue(operationCompleted.get());
      assertTrue(headerSent.get());
    }
  }

  @Test
  public void zeroCopyPutTest() throws IOException, ExecutionException, InterruptedException {
    try (AsyncHttpClient client = asyncHttpClient()) {
      Future<Response> f = client.preparePut("http://localhost:" + port1 + "/").setBody(SIMPLE_TEXT_FILE).execute();
      Response resp = f.get();
      assertNotNull(resp);
      assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
      assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
    }
  }

  @Override
  public AbstractHandler configureHandler() throws Exception {
    return new ZeroCopyHandler();
  }

  @Test
  public void zeroCopyFileTest() throws IOException, ExecutionException, InterruptedException {
    File tmp = new File(System.getProperty("java.io.tmpdir") + File.separator + "zeroCopy.txt");
    tmp.deleteOnExit();
    try (AsyncHttpClient client = asyncHttpClient()) {
      try (OutputStream stream = Files.newOutputStream(tmp.toPath())) {
        Response resp = client.preparePost("http://localhost:" + port1 + "/").setBody(SIMPLE_TEXT_FILE).execute(new AsyncHandler<Response>() {
          public void onThrowable(Throwable t) {
          }

          public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
            stream.write(bodyPart.getBodyPartBytes());
            return State.CONTINUE;
          }

          public State onStatusReceived(HttpResponseStatus responseStatus) {
            return State.CONTINUE;
          }

          public State onHeadersReceived(HttpHeaders headers) {
            return State.CONTINUE;
          }

          public Response onCompleted() {
            return null;
          }
        }).get();
        assertNull(resp);
        assertEquals(SIMPLE_TEXT_FILE.length(), tmp.length());
      }
    }
  }

  @Test
  public void zeroCopyFileWithBodyManipulationTest() throws IOException, ExecutionException, InterruptedException {
    File tmp = new File(System.getProperty("java.io.tmpdir") + File.separator + "zeroCopy.txt");
    tmp.deleteOnExit();
    try (AsyncHttpClient client = asyncHttpClient()) {
      try (OutputStream stream = Files.newOutputStream(tmp.toPath())) {
        Response resp = client.preparePost("http://localhost:" + port1 + "/").setBody(SIMPLE_TEXT_FILE).execute(new AsyncHandler<Response>() {
          public void onThrowable(Throwable t) {
          }

          public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
            stream.write(bodyPart.getBodyPartBytes());

            if (bodyPart.getBodyPartBytes().length == 0) {
              return State.ABORT;
            }

            return State.CONTINUE;
          }

          public State onStatusReceived(HttpResponseStatus responseStatus) {
            return State.CONTINUE;
          }

          public State onHeadersReceived(HttpHeaders headers) {
            return State.CONTINUE;
          }

          public Response onCompleted() {
            return null;
          }
        }).get();
        assertNull(resp);
        assertEquals(SIMPLE_TEXT_FILE.length(), tmp.length());
      }
    }
  }

  private class ZeroCopyHandler extends AbstractHandler {
    public void handle(String s, Request r, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

      int size = 10 * 1024;
      if (httpRequest.getContentLength() > 0) {
        size = httpRequest.getContentLength();
      }
      byte[] bytes = new byte[size];
      if (bytes.length > 0) {
        httpRequest.getInputStream().read(bytes);
        httpResponse.getOutputStream().write(bytes);
      }

      httpResponse.setStatus(200);
      httpResponse.getOutputStream().flush();
    }
  }
}
