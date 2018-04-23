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
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.asynchttpclient.handler.TransferCompletionHandler;
import org.asynchttpclient.handler.TransferListener;
import org.asynchttpclient.request.body.generator.FileBodyGenerator;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.createTempFile;
import static org.testng.Assert.*;

public class TransferListenerTest extends AbstractBasicTest {

  @Override
  public AbstractHandler configureHandler() throws Exception {
    return new BasicHandler();
  }

  @Test
  public void basicGetTest() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      final AtomicReference<Throwable> throwable = new AtomicReference<>();
      final AtomicReference<HttpHeaders> hSent = new AtomicReference<>();
      final AtomicReference<HttpHeaders> hRead = new AtomicReference<>();
      final AtomicReference<byte[]> bb = new AtomicReference<>();
      final AtomicBoolean completed = new AtomicBoolean(false);

      TransferCompletionHandler tl = new TransferCompletionHandler();
      tl.addTransferListener(new TransferListener() {

        public void onRequestHeadersSent(HttpHeaders headers) {
          hSent.set(headers);
        }

        public void onResponseHeadersReceived(HttpHeaders headers) {
          hRead.set(headers);
        }

        public void onBytesReceived(byte[] b) {
          if (b.length != 0)
            bb.set(b);
        }

        public void onBytesSent(long amount, long current, long total) {
        }

        public void onRequestResponseCompleted() {
          completed.set(true);
        }

        public void onThrowable(Throwable t) {
          throwable.set(t);
        }
      });

      Response response = c.prepareGet(getTargetUrl()).execute(tl).get();

      assertNotNull(response);
      assertEquals(response.getStatusCode(), 200);
      assertNotNull(hRead.get());
      assertNotNull(hSent.get());
      assertNull(bb.get());
      assertNull(throwable.get());
    }
  }

  @Test
  public void basicPutFileTest() throws Exception {
    final AtomicReference<Throwable> throwable = new AtomicReference<>();
    final AtomicReference<HttpHeaders> hSent = new AtomicReference<>();
    final AtomicReference<HttpHeaders> hRead = new AtomicReference<>();
    final AtomicInteger bbReceivedLenght = new AtomicInteger(0);
    final AtomicLong bbSentLenght = new AtomicLong(0L);

    final AtomicBoolean completed = new AtomicBoolean(false);

    File file = createTempFile(1024 * 100 * 10);

    int timeout = (int) (file.length() / 1000);

    try (AsyncHttpClient client = asyncHttpClient(config().setConnectTimeout(timeout))) {
      TransferCompletionHandler tl = new TransferCompletionHandler();
      tl.addTransferListener(new TransferListener() {

        public void onRequestHeadersSent(HttpHeaders headers) {
          hSent.set(headers);
        }

        public void onResponseHeadersReceived(HttpHeaders headers) {
          hRead.set(headers);
        }

        public void onBytesReceived(byte[] b) {
          bbReceivedLenght.addAndGet(b.length);
        }

        public void onBytesSent(long amount, long current, long total) {
          bbSentLenght.addAndGet(amount);
        }

        public void onRequestResponseCompleted() {
          completed.set(true);
        }

        public void onThrowable(Throwable t) {
          throwable.set(t);
        }
      });

      Response response = client.preparePut(getTargetUrl()).setBody(file).execute(tl).get();

      assertNotNull(response);
      assertEquals(response.getStatusCode(), 200);
      assertNotNull(hRead.get());
      assertNotNull(hSent.get());
      assertEquals(bbReceivedLenght.get(), file.length(), "Number of received bytes incorrect");
      assertEquals(bbSentLenght.get(), file.length(), "Number of sent bytes incorrect");
    }
  }

  @Test
  public void basicPutFileBodyGeneratorTest() throws Exception {
    try (AsyncHttpClient client = asyncHttpClient()) {
      final AtomicReference<Throwable> throwable = new AtomicReference<>();
      final AtomicReference<HttpHeaders> hSent = new AtomicReference<>();
      final AtomicReference<HttpHeaders> hRead = new AtomicReference<>();
      final AtomicInteger bbReceivedLenght = new AtomicInteger(0);
      final AtomicLong bbSentLenght = new AtomicLong(0L);

      final AtomicBoolean completed = new AtomicBoolean(false);

      File file = createTempFile(1024 * 100 * 10);

      TransferCompletionHandler tl = new TransferCompletionHandler();
      tl.addTransferListener(new TransferListener() {

        public void onRequestHeadersSent(HttpHeaders headers) {
          hSent.set(headers);
        }

        public void onResponseHeadersReceived(HttpHeaders headers) {
          hRead.set(headers);
        }

        public void onBytesReceived(byte[] b) {
          bbReceivedLenght.addAndGet(b.length);
        }

        public void onBytesSent(long amount, long current, long total) {
          bbSentLenght.addAndGet(amount);
        }

        public void onRequestResponseCompleted() {
          completed.set(true);
        }

        public void onThrowable(Throwable t) {
          throwable.set(t);
        }
      });

      Response response = client.preparePut(getTargetUrl()).setBody(new FileBodyGenerator(file)).execute(tl).get();

      assertNotNull(response);
      assertEquals(response.getStatusCode(), 200);
      assertNotNull(hRead.get());
      assertNotNull(hSent.get());
      assertEquals(bbReceivedLenght.get(), file.length(), "Number of received bytes incorrect");
      assertEquals(bbSentLenght.get(), file.length(), "Number of sent bytes incorrect");
    }
  }

  private class BasicHandler extends AbstractHandler {

    public void handle(String s, org.eclipse.jetty.server.Request r, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

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
        int read = 0;
        while (read != -1) {
          read = httpRequest.getInputStream().read(bytes);
          if (read > 0) {
            httpResponse.getOutputStream().write(bytes, 0, read);
          }
        }
      }

      httpResponse.setStatus(200);
      httpResponse.getOutputStream().flush();
      httpResponse.getOutputStream().close();
    }
  }
}
