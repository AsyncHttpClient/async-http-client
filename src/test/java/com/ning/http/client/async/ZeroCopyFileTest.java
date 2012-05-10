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

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Zero copy test which use FileChannel.transfer under the hood . The same SSL test is also covered in {@link com.ning.http.client.async.BasicHttpsTest}
 */
public abstract class ZeroCopyFileTest extends AbstractBasicTest {

    private class ZeroCopyHandler extends AbstractHandler {
        public void handle(String s,
                           Request r,
                           HttpServletRequest httpRequest,
                           HttpServletResponse httpResponse) throws IOException, ServletException {

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

    @Test(groups = {"standalone", "default_provider"})
    public void zeroCopyPostTest() throws IOException, ExecutionException, TimeoutException, InterruptedException, URISyntaxException {
        AsyncHttpClient client = getAsyncHttpClient(null);

        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());
        final AtomicBoolean headerSent = new AtomicBoolean(false);
        final AtomicBoolean operationCompleted = new AtomicBoolean(false);

        Future<Response> f = client.preparePost("http://127.0.0.1:" + port1 + "/").setBody(file).execute(new AsyncCompletionHandler<Response>() {

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
        });
        Response resp = f.get();
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getResponseBody(), "This is a simple test file");
        assertTrue(operationCompleted.get());
        assertTrue(headerSent.get());
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void zeroCopyPutTest() throws IOException, ExecutionException, TimeoutException, InterruptedException, URISyntaxException {
        AsyncHttpClient client = getAsyncHttpClient(null);

        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());

        Future<Response> f = client.preparePut("http://127.0.0.1:" + port1 + "/").setBody(file).execute();
        Response resp = f.get();
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getResponseBody(), "This is a simple test file");
        client.close();

    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ZeroCopyHandler();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void zeroCopyFileTest() throws IOException, ExecutionException, TimeoutException, InterruptedException, URISyntaxException {
        AsyncHttpClient client = getAsyncHttpClient(null);

        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());

        File tmp = new File(System.getProperty("java.io.tmpdir") + File.separator + "zeroCopy.txt");
        tmp.deleteOnExit();
        final FileOutputStream stream = new FileOutputStream(tmp);
        Future<Response> f = client.preparePost("http://127.0.0.1:" + port1 + "/").setBody(file).execute(new AsyncHandler<Response>() {
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
        });
        Response resp = f.get();
        stream.close();
        assertNull(resp);
        assertEquals(file.length(), tmp.length());
        client.close();

    }


    @Test(groups = {"standalone", "default_provider"})
    public void zeroCopyFileWithBodyManipulationTest() throws IOException, ExecutionException, TimeoutException, InterruptedException, URISyntaxException {
        AsyncHttpClient client = getAsyncHttpClient(null);

        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());

        File tmp = new File(System.getProperty("java.io.tmpdir") + File.separator + "zeroCopy.txt");
        tmp.deleteOnExit();
        final FileOutputStream stream = new FileOutputStream(tmp);
        Future<Response> f = client.preparePost("http://127.0.0.1:" + port1 + "/").setBody(file).execute(new AsyncHandler<Response>() {
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
        });
        Response resp = f.get();
        stream.close();
        assertNull(resp);
        assertEquals(file.length(), tmp.length());
        client.close();

    }

}
