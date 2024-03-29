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

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import javax.net.ServerSocketFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * @author Hubert Iwaniuk
 */
public class MultipleHeaderTest extends AbstractBasicTest {
    private static ExecutorService executorService;
    private static ServerSocket serverSocket;
    private static Future<?> voidFuture;

    @Override
    @BeforeEach
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
                    outputStreamWriter.append("HTTP/1.0 200 OK\n" + "Connection: close\n" + "Content-Type: text/plain; charset=iso-8859-1\n" + "X-Duplicated-Header: 2\n"
                            + "X-Duplicated-Header: 1\n" + "\n0\n");
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

    @Override
    @AfterEach
    public void tearDownGlobal() throws Exception {
        voidFuture.cancel(true);
        executorService.shutdownNow();
        serverSocket.close();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testMultipleOtherHeaders() throws Exception {
        final String[] xffHeaders = {null, null};

        try (AsyncHttpClient ahc = asyncHttpClient()) {
            Request req = get("http://localhost:" + port1 + "/MultiOther").build();
            final CountDownLatch latch = new CountDownLatch(1);
            ahc.executeRequest(req, new AsyncHandler<Void>() {
                @Override
                public void onThrowable(Throwable t) {
                    t.printStackTrace(System.out);
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart objectHttpResponseBodyPart) {
                    return State.CONTINUE;
                }

                @Override
                public State onStatusReceived(HttpResponseStatus objectHttpResponseStatus) {
                    return State.CONTINUE;
                }

                @Override
                public State onHeadersReceived(HttpHeaders response) {
                    int i = 0;
                    for (String header : response.getAll("X-Forwarded-For")) {
                        xffHeaders[i++] = header;
                    }
                    latch.countDown();
                    return State.CONTINUE;
                }

                @Override
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

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testMultipleEntityHeaders() throws Exception {
        final String[] clHeaders = {null, null};

        try (AsyncHttpClient ahc = asyncHttpClient()) {
            Request req = get("http://localhost:" + port1 + "/MultiEnt").build();
            final CountDownLatch latch = new CountDownLatch(1);
            ahc.executeRequest(req, new AsyncHandler<Void>() {
                @Override
                public void onThrowable(Throwable t) {
                    t.printStackTrace(System.out);
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart objectHttpResponseBodyPart) {
                    return State.CONTINUE;
                }

                @Override
                public State onStatusReceived(HttpResponseStatus objectHttpResponseStatus) {
                    return State.CONTINUE;
                }

                @Override
                public State onHeadersReceived(HttpHeaders response) {
                    try {
                        int i = 0;
                        for (String header : response.getAll("X-Duplicated-Header")) {
                            clHeaders[i++] = header;
                        }
                    } finally {
                        latch.countDown();
                    }
                    return State.CONTINUE;
                }

                @Override
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
