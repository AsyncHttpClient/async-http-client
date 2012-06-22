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
package com.ning.http.client.async;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
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
public abstract class MultipleHeaderTest extends AbstractBasicTest{
    private ExecutorService executorService;
    private ServerSocket serverSocket;
    private Future<?> voidFuture;

    @Test(groups = {"standalone", "default_provider"})
    public void testMultipleOtherHeaders()
            throws IOException, ExecutionException, TimeoutException, InterruptedException {
        final String[] xffHeaders = new String[]{null, null};

        AsyncHttpClient ahc = getAsyncHttpClient(null);
        Request req = new RequestBuilder("GET").setUrl("http://localhost:" + port1 + "/MultiOther").build();
        final CountDownLatch latch = new CountDownLatch(1);
        ahc.executeRequest(req, new AsyncHandler<Void>() {
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
        try {
            Assert.assertEquals(xffHeaders[0], "abc");
            Assert.assertEquals(xffHeaders[1], "def");
        } catch (AssertionError ex) {
            Assert.assertEquals(xffHeaders[1], "abc");
            Assert.assertEquals(xffHeaders[0], "def");
        }
        ahc.close();
    }


    @Test(groups = {"standalone", "default_provider"})
    public void testMultipleEntityHeaders()
            throws IOException, ExecutionException, TimeoutException, InterruptedException {
        final String[] clHeaders = new String[]{null, null};

        AsyncHttpClient ahc = getAsyncHttpClient(null);
        Request req = new RequestBuilder("GET").setUrl("http://localhost:" + port1 + "/MultiEnt").build();
        final CountDownLatch latch = new CountDownLatch(1);
        ahc.executeRequest(req, new AsyncHandler<Void>() {
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

        // We can predict the order
        try {
            Assert.assertEquals(clHeaders[0], "2");
            Assert.assertEquals(clHeaders[1], "1");
        } catch (Throwable ex) {
            Assert.assertEquals(clHeaders[0], "1");
            Assert.assertEquals(clHeaders[1], "2");
        }
        ahc.close();

    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        port1 = findFreePort();

        serverSocket = new ServerSocket(port1);
        executorService = Executors.newFixedThreadPool(1);
        voidFuture = executorService.submit(new Callable<Void>() {
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
