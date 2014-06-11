/*
 * Copyright (c) 2013-2014 Sonatype, Inc. All rights reserved.
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

package org.asynchttpclient.providers.grizzly;

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.RequestBuilder;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.ssl.SSLContextConfigurator;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.utils.Charsets;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.providers.grizzly.FeedableBodyGenerator.NonBlockingFeeder;

import static org.glassfish.grizzly.http.server.NetworkListener.DEFAULT_NETWORK_HOST;
import static org.glassfish.grizzly.memory.MemoryManager.DEFAULT_MEMORY_MANAGER;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.fail;
import static org.testng.AssertJUnit.assertEquals;

public class GrizzlyFeedableBodyGeneratorTest {

    private static final byte[] DATA =
            "aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ".getBytes(Charsets.ASCII_CHARSET);
    private static final int TEMP_FILE_SIZE = 2 * 1024 * 1024;
    private static final int NON_SECURE_PORT = 9991;
    private static final int SECURE_PORT = 9992;


    private HttpServer server;
    private File tempFile;


    // ------------------------------------------------------------------- Setup


    @BeforeTest
    public void setup() throws Exception {
        generateTempFile();
        server = new HttpServer();
        NetworkListener nonSecure =
                new NetworkListener("nonsecure",
                                    DEFAULT_NETWORK_HOST,
                                    NON_SECURE_PORT);
        NetworkListener secure =
                new NetworkListener("secure",
                                    DEFAULT_NETWORK_HOST,
                                    SECURE_PORT);
        secure.setSecure(true);
        secure.setSSLEngineConfig(createSSLConfig());
        server.addListener(nonSecure);
        server.addListener(secure);
        server.getServerConfiguration().addHttpHandler(new ConsumingHandler(), "/test");
        server.start();
    }


    // --------------------------------------------------------------- Tear Down


    @AfterTest
    public void tearDown() {
        if (!tempFile.delete()) {
            tempFile.deleteOnExit();
        }
        tempFile = null;
        server.shutdownNow();
        server = null;
    }


    // ------------------------------------------------------------ Test Methods


    @Test
    public void testSimpleFeederMultipleThreads() throws Exception {
        doSimpleFeeder(false);
    }

    @Test
    public void testSimpleFeederOverSSLMultipleThreads() throws Exception {
        doSimpleFeeder(true);
    }

    @Test
    public void testNonBlockingFeederMultipleThreads() throws Exception {
        doNonBlockingFeeder(false);
    }

    @Test
    public void testNonBlockingFeederOverSSLMultipleThreads() throws Exception {
        doNonBlockingFeeder(true);
    }

    // --------------------------------------------------------- Private Methods


    private void doSimpleFeeder(final boolean secure) {
        final int threadCount = 10;
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final int port = (secure ? SECURE_PORT : NON_SECURE_PORT);
        final String scheme = (secure ? "https" : "http");
        ExecutorService service = Executors.newFixedThreadPool(threadCount);

        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder()
                .setMaxConnectionsPerHost(60)
                .setMaxConnectionsTotal(60)
                .build();
        final AsyncHttpClient client =
                new DefaultAsyncHttpClient(new GrizzlyAsyncHttpProvider(config), config);
        final int[] statusCodes = new int[threadCount];
        final int[] totalsReceived = new int[threadCount];
        final Throwable[] errors = new Throwable[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            service.execute(new Runnable() {
                @Override
                public void run() {
                    FeedableBodyGenerator generator =
                            new FeedableBodyGenerator();
                    FeedableBodyGenerator.SimpleFeeder simpleFeeder =
                            new FeedableBodyGenerator.SimpleFeeder(generator) {
                                @Override
                                public void flush() throws IOException {
                                    FileInputStream in = null;
                                    try {
                                        final byte[] bytesIn = new byte[2048];
                                        in = new FileInputStream(tempFile);
                                        int read;
                                        while ((read = in.read(bytesIn)) != -1) {
                                            final Buffer b =
                                                    Buffers.wrap(
                                                            DEFAULT_MEMORY_MANAGER,
                                                            bytesIn,
                                                            0,
                                                            read);
                                            feed(b, false);
                                        }
                                        feed(Buffers.EMPTY_BUFFER, true);
                                    } finally {
                                        if (in != null) {
                                            try {
                                                in.close();
                                            } catch (IOException ignored) {
                                            }
                                        }
                                    }
                                }
                            };
                    generator.setFeeder(simpleFeeder);
                    generator.setMaxPendingBytes(10000);

                    RequestBuilder builder = new RequestBuilder("POST");
                    builder.setUrl(scheme + "://localhost:" + port + "/test");
                    builder.setBody(generator);
                    try {
                        client.executeRequest(builder.build(),
                                new AsyncCompletionHandler<org.asynchttpclient.Response>() {
                                    @Override
                                    public org.asynchttpclient.Response onCompleted(org.asynchttpclient.Response response)
                                    throws Exception {
                                        try {
                                            totalsReceived[idx] = Integer.parseInt(response.getHeader("x-total"));
                                        } catch (Exception e) {
                                            errors[idx] = e;
                                        }
                                        statusCodes[idx] = response.getStatusCode();
                                        latch.countDown();
                                        return response;
                                    }

                                    @Override
                                    public void onThrowable(Throwable t) {
                                        errors[idx] = t;
                                        t.printStackTrace();
                                        latch.countDown();
                                    }
                               });
                    } catch (IOException e) {
                        errors[idx] = e;
                        latch.countDown();
                    }
                }
            });
        }

        try {
            latch.await(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            fail("Latch interrupted");
        }

        for (int i = 0; i < threadCount; i++) {
            assertEquals(200, statusCodes[i]);
            assertNull(errors[i]);
            assertEquals(tempFile.length(), totalsReceived[i]);
        }
    }

    private void doNonBlockingFeeder(final boolean secure) {
        final int threadCount = 10;
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final int port = (secure ? SECURE_PORT : NON_SECURE_PORT);
        final String scheme = (secure ? "https" : "http");
        final ExecutorService service = Executors.newCachedThreadPool();

        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder()
                .setMaxConnectionsPerHost(60)
                .setMaxConnectionsTotal(60)
                .build();
        final AsyncHttpClient client =
                new DefaultAsyncHttpClient(new GrizzlyAsyncHttpProvider(config), config);
        final int[] statusCodes = new int[threadCount];
        final int[] totalsReceived = new int[threadCount];
        final Throwable[] errors = new Throwable[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            service.execute(new Runnable() {
                @Override
                public void run() {
                    FeedableBodyGenerator generator =
                            new FeedableBodyGenerator();
                    FeedableBodyGenerator.NonBlockingFeeder nonBlockingFeeder =
                            new FeedableBodyGenerator.NonBlockingFeeder(generator) {
                                private final Random r = new Random();
                                private final InputStream in;
                                private final byte[] bytesIn = new byte[2048];
                                private boolean isDone;
                                
                                {
                                    try {
                                        in = new FileInputStream(tempFile);
                                    } catch (IOException e) {
                                        throw new IllegalStateException(e);
                                    }
                                }

                                @Override
                                public void canFeed() throws IOException {
                                    final int read = in.read(bytesIn);
                                    if (read == -1) {
                                        isDone = true;
                                        feed(Buffers.EMPTY_BUFFER, true);
                                        return;
                                    }

                                    final Buffer b =
                                            Buffers.wrap(
                                                    DEFAULT_MEMORY_MANAGER,
                                                    bytesIn,
                                                    0,
                                                    read);
                                    feed(b, false);
                                }

                                @Override
                                public boolean isDone() {
                                    return isDone;
                                }

                                @Override
                                public boolean isReady() {
                                    // simulate real-life usecase, where data could not be ready
                                    return r.nextInt(100) < 80;
                                }

                                @Override
                                public void notifyReadyToFeed(
                                        final NonBlockingFeeder.ReadyToFeedListener listener) {
                                    service.execute(new Runnable() {

                                        public void run() {
                                            try {
                                                Thread.sleep(2);
                                            } catch (InterruptedException e) {
                                            }
                                            
                                            listener.ready();
                                        }
                                        
                                    });
                                }
                            };
                    generator.setFeeder(nonBlockingFeeder);
                    generator.setMaxPendingBytes(10000);

                    RequestBuilder builder = new RequestBuilder("POST");
                    builder.setUrl(scheme + "://localhost:" + port + "/test");
                    builder.setBody(generator);
                    try {
                        client.executeRequest(builder.build(),
                                new AsyncCompletionHandler<org.asynchttpclient.Response>() {
                                    @Override
                                    public org.asynchttpclient.Response onCompleted(org.asynchttpclient.Response response)
                                    throws Exception {
                                        try {
                                            totalsReceived[idx] = Integer.parseInt(response.getHeader("x-total"));
                                        } catch (Exception e) {
                                            errors[idx] = e;
                                        }
                                        statusCodes[idx] = response.getStatusCode();
                                        latch.countDown();
                                        return response;
                                    }

                                    @Override
                                    public void onThrowable(Throwable t) {
                                        errors[idx] = t;
                                        t.printStackTrace();
                                        latch.countDown();
                                    }
                               });
                    } catch (IOException e) {
                        errors[idx] = e;
                        latch.countDown();
                    }
                }
            });
        }

        try {
            latch.await(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            fail("Latch interrupted");
        } finally {
            service.shutdownNow();
        }

        for (int i = 0; i < threadCount; i++) {
            assertEquals(200, statusCodes[i]);
            assertNull(errors[i]);
            assertEquals(tempFile.length(), totalsReceived[i]);
        }
    }
    
    private static SSLEngineConfigurator createSSLConfig()
    throws Exception {
        final SSLContextConfigurator sslContextConfigurator =
                new SSLContextConfigurator();
        final ClassLoader cl = GrizzlyFeedableBodyGeneratorTest.class.getClassLoader();
        // override system properties
        final URL cacertsUrl = cl.getResource("ssltest-cacerts.jks");
        if (cacertsUrl != null) {
            sslContextConfigurator.setTrustStoreFile(cacertsUrl.getFile());
            sslContextConfigurator.setTrustStorePass("changeit");
        }

        // override system properties
        final URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        if (keystoreUrl != null) {
            sslContextConfigurator.setKeyStoreFile(keystoreUrl.getFile());
            sslContextConfigurator.setKeyStorePass("changeit");
        }

        return new SSLEngineConfigurator(
                sslContextConfigurator.createSSLContext(),
                false, false, false);
    }


    private void generateTempFile() throws IOException {
        tempFile = File.createTempFile("feedable", null);
        int total = 0;
        byte[] chunk = new byte[1024];
        Random r = new Random(System.currentTimeMillis());
        FileOutputStream out = new FileOutputStream(tempFile);
        while (total < TEMP_FILE_SIZE) {
            for (int i = 0; i < chunk.length; i++) {
                chunk[i] = DATA[r.nextInt(DATA.length)];
            }
            out.write(chunk);
            total += chunk.length;
        }
        out.flush();
        out.close();
    }


    // ---------------------------------------------------------- Nested Classes


    private static final class ConsumingHandler extends HttpHandler {


        // -------------------------------------------- Methods from HttpHandler


        @Override
        public void service(Request request, Response response)
        throws Exception {
            int total = 0;
            byte[] bytesIn = new byte[2048];
            InputStream in = request.getInputStream();
            int read;
            while ((read = in.read(bytesIn)) != -1) {
                total += read;
                Thread.sleep(5);
            }
            response.addHeader("X-Total", Integer.toString(total));
        }

    } // END ConsumingHandler

}
