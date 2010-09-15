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
 *
 */
public class ZeroCopyFileTest extends AbstractBasicTest {

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
            httpResponse.getOutputStream().close();
        }
    }

    @Test(groups = "standalone")
    public void zeroCopyPostTest() throws IOException, ExecutionException, TimeoutException, InterruptedException, URISyntaxException {
        AsyncHttpClient client = new AsyncHttpClient();

        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());
        final AtomicBoolean headerSent = new AtomicBoolean(false);
        final AtomicBoolean operationCompleted = new AtomicBoolean(false);


        Future<Response> f = client.preparePost("http://127.0.0.1:" + port1 + "/").setBody(file).execute(new AsyncCompletionHandler() {

            public STATE onHeaderWriteCompleted() {
                headerSent.set(true);
                return STATE.CONTINUE;
            }

            public STATE onContentWriteCompleted() {
                operationCompleted.set(true);
                return STATE.CONTINUE;
            }

            @Override
            public Object onCompleted(Response response) throws Exception {
                return response;
            }
        });
        Response resp = f.get();
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getResponseBody(), "This is a simple test file");
        assertTrue(operationCompleted.get());
        assertTrue(headerSent.get());
    }

    @Test(groups = "standalone")
    public void zeroCopyPutTest() throws IOException, ExecutionException, TimeoutException, InterruptedException, URISyntaxException {
        AsyncHttpClient client = new AsyncHttpClient();

        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());

        Future<Response> f = client.preparePut("http://127.0.0.1:" + port1 + "/").setBody(file).execute();
        Response resp = f.get();
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getResponseBody(), "This is a simple test file");
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ZeroCopyHandler();
    }

    @Test(groups = "standalone")
    public void zeroCopyFileTest() throws IOException, ExecutionException, TimeoutException, InterruptedException, URISyntaxException {
        AsyncHttpClient client = new AsyncHttpClient();

        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());

        File tmp = new File(System.getProperty("java.io.tmpdir") + "zeroCopy.txt");
        final FileOutputStream stream = new FileOutputStream(tmp);
        Future<Response> f = client.preparePost("http://127.0.0.1:" + port1 + "/").setBody(file).execute(new AsyncHandler<Response>() {
            public void onThrowable(Throwable t) {
            }

            public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                bodyPart.writeTo(stream);
                return STATE.CONTINUE;
            }

            public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                return STATE.CONTINUE;              }

            public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
                return STATE.CONTINUE;              }

            public Response onCompleted() throws Exception {
                return null;
            }
        });
        Response resp = f.get();
        stream.close();        
        assertNull(resp);
        assertEquals(file.length(), tmp.length());
    }
}
