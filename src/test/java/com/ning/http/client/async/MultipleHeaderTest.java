/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
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
package com.ning.http.client.async;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.RequestType;
import org.apache.log4j.BasicConfigurator;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author Hubert Iwaniuk
 */
public class MultipleHeaderTest {
    private ExecutorService executorService;
    private ServerSocket serverSocket;
    private static final int PORT = 2929;
    private Future voidFuture;

    @Test(groups = "standalone")
    public void testMultipleOtherHeaders()
            throws IOException, ExecutionException, TimeoutException, InterruptedException {
        final String[] xffHeaders = new String[]{null, null};

        AsyncHttpClient ahc = new AsyncHttpClient();
        Request req = new RequestBuilder(RequestType.GET).setUrl("http://localhost:" + PORT + "/MultiOther").build();
        final CountDownLatch latch = new CountDownLatch(1);
        ahc.executeRequest(req, new AsyncHandler() {
            public void onThrowable(Throwable t) {
                t.printStackTrace(System.out);
            }

            public STATE onBodyPartReceived(HttpResponseBodyPart objectHttpResponseBodyPart) throws Exception {
                return STATE.CONTINUE;
            }

            public STATE onStatusReceived(HttpResponseStatus objectHttpResponseStatus) throws Exception {
                return STATE.CONTINUE;
            }

            public STATE onHeadersReceived(HttpResponseHeaders response) throws Exception {
                int i = 0;
                for (String header : response.getHeaders().get("X-Forwarded-For")) {
                    xffHeaders[i++] = header;
                }
                latch.countDown();
                return STATE.CONTINUE;
            }

            public Void onCompleted() throws Exception {
                return null;
            }
        }).get(3, TimeUnit.SECONDS);

        if (!latch.await(2, TimeUnit.SECONDS)) {
            Assert.fail("Time out");
        }
        Assert.assertNotNull(xffHeaders[0]);
        Assert.assertNotNull(xffHeaders[1]);
        Assert.assertEquals(xffHeaders[0], "abc");
        Assert.assertEquals(xffHeaders[1], "def");
    }


    @Test(groups = "standalone")
    public void testMultipleEntityHeaders()
            throws IOException, ExecutionException, TimeoutException, InterruptedException {
        final String[] clHeaders = new String[]{null, null};

        AsyncHttpClient ahc = new AsyncHttpClient();
        Request req = new RequestBuilder(RequestType.GET).setUrl("http://localhost:" + PORT + "/MultiEnt").build();
        final CountDownLatch latch = new CountDownLatch(1);
        ahc.executeRequest(req, new AsyncHandler() {
            public void onThrowable(Throwable t) {
                t.printStackTrace(System.out);
            }

            public STATE onBodyPartReceived(HttpResponseBodyPart objectHttpResponseBodyPart) throws Exception {
                return STATE.CONTINUE;
            }

            public STATE onStatusReceived(HttpResponseStatus objectHttpResponseStatus) throws Exception {
                return STATE.CONTINUE;
            }

            public STATE onHeadersReceived(HttpResponseHeaders response) throws Exception {
                try {
                    int i = 0;
                    for (String header : response.getHeaders().get("Content-Length")) {
                        clHeaders[i++] = header;
                    }
                } finally {
                    latch.countDown();
                }
                return STATE.CONTINUE;
            }

            public Void onCompleted() throws Exception {
                return null;
            }
        }).get(3, TimeUnit.SECONDS);

        if (!latch.await(2, TimeUnit.SECONDS)) {
            Assert.fail("Time out");
        }
        Assert.assertNotNull(clHeaders[0]);
        Assert.assertNotNull(clHeaders[1]);
        Assert.assertEquals(clHeaders[0], "2");
        Assert.assertEquals(clHeaders[1], "1");
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        BasicConfigurator.configure();
        serverSocket = new ServerSocket(PORT);
        executorService = Executors.newFixedThreadPool(1);
        voidFuture = executorService.submit(new Callable() {
            public Void call() throws Exception {
                Socket socket;
                while ((socket = serverSocket.accept()) != null) {
                    InputStream inputStream = socket.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                    String req = reader.readLine().split(" ")[1];
                    int i = inputStream.available();
                    long l = inputStream.skip(i);
                    Assert.assertEquals(l, i);
                    socket.shutdownInput();
                    if (req.endsWith("MultiEnt")) {
                        OutputStreamWriter outputStreamWriter = new OutputStreamWriter(socket.getOutputStream());
                        outputStreamWriter.append("HTTP/1.0 200 OK\n" +
                                "Connection: close\n" +
                                "Content-Type: text/plain; charset=iso-8859-1\n" +
                                "Content-Length: 2\n" +
                                "Content-Length: 1\n" +
                                "\n0\n");
                        outputStreamWriter.flush();
                        socket.shutdownOutput();
                    } else if (req.endsWith("MultiOther")) {
                        OutputStreamWriter outputStreamWriter = new OutputStreamWriter(socket.getOutputStream());
                        outputStreamWriter.append("HTTP/1.0 200 OK\n" +
                                "Connection: close\n" +
                                "Content-Type: text/plain; charset=iso-8859-1\n" +
                                "Content-Length: 1\n" +
                                "X-Forwarded-For: abc\n" +
                                "X-Forwarded-For: def\n" +
                                "\n0\n");
                        outputStreamWriter.flush();
                        socket.shutdownOutput();
                    }
                }
                return null;
            }
        });
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        voidFuture.cancel(true);
        executorService.shutdownNow();
        serverSocket.close();
    }
}