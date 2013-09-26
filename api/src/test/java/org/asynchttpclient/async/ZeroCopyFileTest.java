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

import static org.asynchttpclient.async.util.TestUtils.*;
import static org.testng.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

/**
 * Zero copy test which use FileChannel.transfer under the hood . The same SSL test is also covered in {@link BasicHttpsTest}
 */
public abstract class ZeroCopyFileTest extends AbstractBasicTest {

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

    @Test(groups = { "standalone", "default_provider" })
    public void zeroCopyPostTest() throws IOException, ExecutionException, TimeoutException, InterruptedException, URISyntaxException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            final AtomicBoolean headerSent = new AtomicBoolean(false);
            final AtomicBoolean operationCompleted = new AtomicBoolean(false);

            Response resp = client.preparePost("http://127.0.0.1:" + port1 + "/").setBody(SIMPLE_TEXT_FILE).execute(new AsyncCompletionHandler<Response>() {

                public STATE onHeaderWriteCompleted() {
                    headerSent.set(true);
                    return STATE.CONTINUE;
                }

                public STATE onContentWriteCompleted() {
                    operationCompleted.set(true);
                    return STATE.CONTINUE;
                }

                @Override
                public Response onCompleted(Response response) throws Exception {
                    return response;
                }
            }).get();
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
            assertTrue(operationCompleted.get());
            assertTrue(headerSent.get());
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void zeroCopyPutTest() throws IOException, ExecutionException, TimeoutException, InterruptedException, URISyntaxException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Future<Response> f = client.preparePut("http://127.0.0.1:" + port1 + "/").setBody(SIMPLE_TEXT_FILE).execute();
            Response resp = f.get();
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        } finally {
            client.close();
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ZeroCopyHandler();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void zeroCopyFileTest() throws IOException, ExecutionException, TimeoutException, InterruptedException, URISyntaxException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        File tmp = new File(System.getProperty("java.io.tmpdir") + File.separator + "zeroCopy.txt");
        tmp.deleteOnExit();
        final FileOutputStream stream = new FileOutputStream(tmp);
        try {
            Response resp = client.preparePost("http://127.0.0.1:" + port1 + "/").setBody(SIMPLE_TEXT_FILE).execute(new AsyncHandler<Response>() {
                public void onThrowable(Throwable t) {
                }

                public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                    bodyPart.writeTo(stream);
                    return STATE.CONTINUE;
                }

                public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                    return STATE.CONTINUE;
                }

                public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
                    return STATE.CONTINUE;
                }

                public Response onCompleted() throws Exception {
                    return null;
                }
            }).get();
            assertNull(resp);
            assertEquals(SIMPLE_TEXT_FILE.length(), tmp.length());
        } finally {
            stream.close();
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void zeroCopyFileWithBodyManipulationTest() throws IOException, ExecutionException, TimeoutException, InterruptedException, URISyntaxException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        File tmp = new File(System.getProperty("java.io.tmpdir") + File.separator + "zeroCopy.txt");
        tmp.deleteOnExit();
        final FileOutputStream stream = new FileOutputStream(tmp);
        try {

            Response resp = client.preparePost("http://127.0.0.1:" + port1 + "/").setBody(SIMPLE_TEXT_FILE).execute(new AsyncHandler<Response>() {
                public void onThrowable(Throwable t) {
                }

                public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                    bodyPart.writeTo(stream);

                    if (bodyPart.getBodyPartBytes().length == 0) {
                        return STATE.ABORT;
                    }

                    return STATE.CONTINUE;
                }

                public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                    return STATE.CONTINUE;
                }

                public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
                    return STATE.CONTINUE;
                }

                public Response onCompleted() throws Exception {
                    return null;
                }
            }).get();
            assertNull(resp);
            assertEquals(SIMPLE_TEXT_FILE.length(), tmp.length());
        } finally {
            stream.close();
            client.close();
        }
    }
}
