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

import static org.asynchttpclient.async.util.TestUtils.createTempFile;
import static org.testng.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
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
import org.testng.annotations.Test;

public abstract class TransferListenerTest extends AbstractBasicTest {

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

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new BasicHandler();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicGetTest() throws Exception {
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
                assertNull(bb.get());
                assertNull(throwable.get());
            } catch (IOException ex) {
                fail("Should have timed out");
            }
        } finally {
            c.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicPutFileTest() throws Exception {
        final AtomicReference<Throwable> throwable = new AtomicReference<Throwable>();
        final AtomicReference<FluentCaseInsensitiveStringsMap> hSent = new AtomicReference<FluentCaseInsensitiveStringsMap>();
        final AtomicReference<FluentCaseInsensitiveStringsMap> hRead = new AtomicReference<FluentCaseInsensitiveStringsMap>();
        final AtomicInteger bbReceivedLenght = new AtomicInteger(0);
        final AtomicLong bbSentLenght = new AtomicLong(0L);

        final AtomicBoolean completed = new AtomicBoolean(false);

        File file = createTempFile(1024 * 100 * 10);

        int timeout = (int) (file.length() / 1000);
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
                assertEquals(bbReceivedLenght.get(), file.length(), "Number of received bytes incorrect");
                assertEquals(bbSentLenght.get(), file.length(), "Number of sent bytes incorrect");
            } catch (IOException ex) {
                fail("Should have timed out");
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicPutFileBodyGeneratorTest() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final AtomicReference<Throwable> throwable = new AtomicReference<Throwable>();
            final AtomicReference<FluentCaseInsensitiveStringsMap> hSent = new AtomicReference<FluentCaseInsensitiveStringsMap>();
            final AtomicReference<FluentCaseInsensitiveStringsMap> hRead = new AtomicReference<FluentCaseInsensitiveStringsMap>();
            final AtomicInteger bbReceivedLenght = new AtomicInteger(0);
            final AtomicLong bbSentLenght = new AtomicLong(0L);

            final AtomicBoolean completed = new AtomicBoolean(false);

            File file = createTempFile(1024 * 100 * 10);

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
                assertEquals(bbReceivedLenght.get(), file.length(), "Number of received bytes incorrect");
                assertEquals(bbSentLenght.get(), file.length(), "Number of sent bytes incorrect");
            } catch (IOException ex) {
                fail("Should have timed out");
            }
        } finally {
            client.close();
        }
    }
}
