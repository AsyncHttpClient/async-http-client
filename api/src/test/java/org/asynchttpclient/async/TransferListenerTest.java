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
package org.asynchttpclient.async;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.Response;
import org.asynchttpclient.generators.FileBodyGenerator;
import org.asynchttpclient.listener.TransferCompletionHandler;
import org.asynchttpclient.listener.TransferListener;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.Assert;
import org.testng.annotations.Test;

public abstract class TransferListenerTest extends AbstractBasicTest {

    private static final File TMP = new File(System.getProperty("java.io.tmpdir"), "ahc-tests-" + UUID.randomUUID().toString().substring(0, 8));
    private static final byte[] PATTERN_BYTES = "RatherLargeFileRatherLargeFileRatherLargeFileRatherLargeFile".getBytes(Charset.forName("UTF-16"));

    static {
        TMP.mkdirs();
        TMP.deleteOnExit();
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
                httpRequest.getInputStream().read(bytes);
                httpResponse.getOutputStream().write(bytes);
            }

            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new BasicHandler();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicGetTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final AtomicReference<Throwable> throwable = new AtomicReference<Throwable>();
            final AtomicReference<FluentCaseInsensitiveStringsMap> hSent = new AtomicReference<FluentCaseInsensitiveStringsMap>();
            final AtomicReference<FluentCaseInsensitiveStringsMap> hRead = new AtomicReference<FluentCaseInsensitiveStringsMap>();
            final AtomicReference<byte[]> bb = new AtomicReference<byte[]>();
            final AtomicBoolean completed = new AtomicBoolean(false);

            TransferCompletionHandler tl = new TransferCompletionHandler();
            tl.addTransferListener(new TransferListener() {

                public void onRequestHeadersSent(FluentCaseInsensitiveStringsMap headers) {
                    hSent.set(headers);
                }

                public void onResponseHeadersReceived(FluentCaseInsensitiveStringsMap headers) {
                    hRead.set(headers);
                }

                public void onBytesReceived(byte[] b) {
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

            try {
                Response response = c.prepareGet(getTargetUrl()).execute(tl).get();

                assertNotNull(response);
                assertEquals(response.getStatusCode(), 200);
                assertNotNull(hRead.get());
                assertNotNull(hSent.get());
                assertNotNull(bb.get());
                assertNull(throwable.get());
            } catch (IOException ex) {
                fail("Should have timed out");
            }
        } finally {
            c.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicPutTest() throws Throwable {
        final AtomicReference<Throwable> throwable = new AtomicReference<Throwable>();
        final AtomicReference<FluentCaseInsensitiveStringsMap> hSent = new AtomicReference<FluentCaseInsensitiveStringsMap>();
        final AtomicReference<FluentCaseInsensitiveStringsMap> hRead = new AtomicReference<FluentCaseInsensitiveStringsMap>();
        final AtomicInteger bbReceivedLenght = new AtomicInteger(0);
        final AtomicLong bbSentLenght = new AtomicLong(0L);

        final AtomicBoolean completed = new AtomicBoolean(false);

        byte[] bytes = "RatherLargeFileRatherLargeFileRatherLargeFileRatherLargeFile".getBytes("UTF-16");
        long repeats = (1024 * 100 * 10 / bytes.length) + 1;
        File file = createTempFile(bytes, (int) repeats);
        long expectedFileSize = PATTERN_BYTES.length * repeats;
        Assert.assertEquals(expectedFileSize, file.length(), "Invalid file length");

        int timeout = (int) (repeats / 1000);
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setConnectionTimeoutInMs(timeout).build());

        try {
            TransferCompletionHandler tl = new TransferCompletionHandler();
            tl.addTransferListener(new TransferListener() {

                public void onRequestHeadersSent(FluentCaseInsensitiveStringsMap headers) {
                    hSent.set(headers);
                }

                public void onResponseHeadersReceived(FluentCaseInsensitiveStringsMap headers) {
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

            try {
                Response response = client.preparePut(getTargetUrl()).setBody(file).execute(tl).get();

                assertNotNull(response);
                assertEquals(response.getStatusCode(), 200);
                assertNotNull(hRead.get());
                assertNotNull(hSent.get());
                assertEquals(bbReceivedLenght.get(), expectedFileSize, "Number of received bytes incorrect");
                assertEquals(bbSentLenght.get(), expectedFileSize, "Number of sent bytes incorrect");
            } catch (IOException ex) {
                fail("Should have timed out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicPutBodyTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final AtomicReference<Throwable> throwable = new AtomicReference<Throwable>();
            final AtomicReference<FluentCaseInsensitiveStringsMap> hSent = new AtomicReference<FluentCaseInsensitiveStringsMap>();
            final AtomicReference<FluentCaseInsensitiveStringsMap> hRead = new AtomicReference<FluentCaseInsensitiveStringsMap>();
            final AtomicInteger bbReceivedLenght = new AtomicInteger(0);
            final AtomicLong bbSentLenght = new AtomicLong(0L);

            final AtomicBoolean completed = new AtomicBoolean(false);

            long repeats = (1024 * 100 * 10 / PATTERN_BYTES.length) + 1;
            File file = createTempFile(PATTERN_BYTES, (int) repeats);
            long expectedFileSize = PATTERN_BYTES.length * repeats;
            Assert.assertEquals(expectedFileSize, file.length(), "Invalid file length");

            TransferCompletionHandler tl = new TransferCompletionHandler();
            tl.addTransferListener(new TransferListener() {

                public void onRequestHeadersSent(FluentCaseInsensitiveStringsMap headers) {
                    hSent.set(headers);
                }

                public void onResponseHeadersReceived(FluentCaseInsensitiveStringsMap headers) {
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

            try {
                Response response = client.preparePut(getTargetUrl()).setBody(new FileBodyGenerator(file)).execute(tl).get();

                assertNotNull(response);
                assertEquals(response.getStatusCode(), 200);
                assertNotNull(hRead.get());
                assertNotNull(hSent.get());
                assertEquals(bbReceivedLenght.get(), expectedFileSize, "Number of received bytes incorrect");
                assertEquals(bbSentLenght.get(), expectedFileSize, "Number of sent bytes incorrect");
            } catch (IOException ex) {
                fail("Should have timed out");
            }
        } finally {
            client.close();
        }
    }

    public String getTargetUrl() {
        return String.format("http://127.0.0.1:%d/foo/test", port1);
    }

    public static File createTempFile(byte[] pattern, int repeat) throws IOException {
        File tmpFile = File.createTempFile("tmpfile-", ".data", TMP);
        tmpFile.deleteOnExit();
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmpFile);
            for (int i = 0; i < repeat; i++) {
                out.write(pattern);
            }
        } finally {
            if (out != null) {
                out.close();
            }
        }

        return tmpFile;
    }
}
