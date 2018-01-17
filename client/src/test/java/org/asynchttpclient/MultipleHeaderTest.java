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

import io.netty.handler.codec.http.HttpHeaders;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.net.ServerSocketFactory;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.get;
import static org.testng.Assert.*;

/**
 * @author Hubert Iwaniuk
 */
public class MultipleHeaderTest extends AbstractBasicTest {
  private ExecutorService executorService;
  private ServerSocket serverSocket;
  private Future<?> voidFuture;

  @BeforeClass
  public void setUpGlobal() throws Exception {
    serverSocket = ServerSocketFactory.getDefault().createServerSocket(0);
    port1 = serverSocket.getLocalPort();
    executorService = Executors.newFixedThreadPool(1);
    voidFuture = executorService.submit(() -> {
        Socket socket;
        while ((socket = serverSocket.accept()) != null) {
          InputStream inputStream = socket.getInputStream();
          BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
          String req = reader.readLine().split(" ")[1];
          int i = inputStream.available();
          long l = inputStream.skip(i);
          assertEquals(l, i);
          socket.shutdownInput();
          if (req.endsWith("MultiEnt")) {
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(socket.getOutputStream());
            outputStreamWriter.append("HTTP/1.0 200 OK\n" + "Connection: close\n" + "Content-Type: text/plain; charset=iso-8859-1\n" + "Content-Length: 2\n"
                    + "Content-Length: 1\n" + "\n0\n");
            outputStreamWriter.flush();
            socket.shutdownOutput();
          } else if (req.endsWith("MultiOther")) {
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(socket.getOutputStream());
            outputStreamWriter.append("HTTP/1.0 200 OK\n" + "Connection: close\n" + "Content-Type: text/plain; charset=iso-8859-1\n" + "Content-Length: 1\n"
                    + "X-Forwarded-For: abc\n" + "X-Forwarded-For: def\n" + "\n0\n");
            outputStreamWriter.flush();
            socket.shutdownOutput();
          }
        }
        return null;
      });
  }

  @AfterClass(alwaysRun = true)
  public void tearDownGlobal() throws Exception {
    voidFuture.cancel(true);
    executorService.shutdownNow();
    serverSocket.close();
  }

  @Test
  public void testMultipleOtherHeaders() throws IOException, ExecutionException, TimeoutException, InterruptedException {
    final String[] xffHeaders = new String[]{null, null};

    try (AsyncHttpClient ahc = asyncHttpClient()) {
      Request req = get("http://localhost:" + port1 + "/MultiOther").build();
      final CountDownLatch latch = new CountDownLatch(1);
      ahc.executeRequest(req, new AsyncHandler<Void>() {
        public void onThrowable(Throwable t) {
          t.printStackTrace(System.out);
        }

        public State onBodyPartReceived(HttpResponseBodyPart objectHttpResponseBodyPart) {
          return State.CONTINUE;
        }

        public State onStatusReceived(HttpResponseStatus objectHttpResponseStatus) {
          return State.CONTINUE;
        }

        public State onHeadersReceived(HttpHeaders response) {
          int i = 0;
          for (String header : response.getAll("X-Forwarded-For")) {
            xffHeaders[i++] = header;
          }
          latch.countDown();
          return State.CONTINUE;
        }

        public Void onCompleted() {
          return null;
        }
      }).get(3, TimeUnit.SECONDS);

      if (!latch.await(2, TimeUnit.SECONDS)) {
        fail("Time out");
      }
      assertNotNull(xffHeaders[0]);
      assertNotNull(xffHeaders[1]);
      try {
        assertEquals(xffHeaders[0], "abc");
        assertEquals(xffHeaders[1], "def");
      } catch (AssertionError ex) {
        assertEquals(xffHeaders[1], "abc");
        assertEquals(xffHeaders[0], "def");
      }
    }
  }

  @Test
  public void testMultipleEntityHeaders() throws IOException, ExecutionException, TimeoutException, InterruptedException {
    final String[] clHeaders = new String[]{null, null};

    try (AsyncHttpClient ahc = asyncHttpClient()) {
      Request req = get("http://localhost:" + port1 + "/MultiEnt").build();
      final CountDownLatch latch = new CountDownLatch(1);
      ahc.executeRequest(req, new AsyncHandler<Void>() {
        public void onThrowable(Throwable t) {
          t.printStackTrace(System.out);
        }

        public State onBodyPartReceived(HttpResponseBodyPart objectHttpResponseBodyPart) {
          return State.CONTINUE;
        }

        public State onStatusReceived(HttpResponseStatus objectHttpResponseStatus) {
          return State.CONTINUE;
        }

        public State onHeadersReceived(HttpHeaders response) {
          try {
            int i = 0;
            for (String header : response.getAll(CONTENT_LENGTH)) {
              clHeaders[i++] = header;
            }
          } finally {
            latch.countDown();
          }
          return State.CONTINUE;
        }

        public Void onCompleted() {
          return null;
        }
      }).get(3, TimeUnit.SECONDS);

      if (!latch.await(2, TimeUnit.SECONDS)) {
        fail("Time out");
      }
      assertNotNull(clHeaders[0]);
      assertNotNull(clHeaders[1]);

      // We can predict the order
      try {
        assertEquals(clHeaders[0], "2");
        assertEquals(clHeaders[1], "1");
      } catch (Throwable ex) {
        assertEquals(clHeaders[0], "1");
        assertEquals(clHeaders[1], "2");
      }
    }
  }
}
