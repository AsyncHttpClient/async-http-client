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

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Tests POST request with Query String.
 *
 * @author Hubert Iwaniuk
 */
public class ZeroCopyFileTest extends AbstractBasicTest {

    /** POST with QS server part. */
    private class ZeroCopyHandler extends AbstractHandler {
        public void handle(String s,
                           Request r,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException {
            
            ServletInputStream is = request.getInputStream();
            response.setStatus(HttpServletResponse.SC_OK);
            byte buf[] = new byte["This is a simple test file".length()];
            is.readLine(buf, 0, buf.length);
            ServletOutputStream os = response.getOutputStream();
            os.println(new String(buf));
            os.flush();
            os.close();
        }
    }

    @Test(groups = "standalone")
    public void zeroCopyPostTest() throws IOException, ExecutionException, TimeoutException, InterruptedException, URISyntaxException {
        AsyncHttpClient client = new AsyncHttpClient();

        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());
        
        Future<Response> f = client.preparePost("http://127.0.0.1:" + port1 + "/").setFile(file).execute();
        Response resp = f.get();
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getResponseBody(), "This is a simple test file\r\n");
    }

    @Test(groups = "standalone")
    public void zeroCopyPutTest() throws IOException, ExecutionException, TimeoutException, InterruptedException, URISyntaxException {
        AsyncHttpClient client = new AsyncHttpClient();

        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());

        Future<Response> f = client.preparePut("http://127.0.0.1:" + port1 + "/").setFile(file).execute();
        Response resp = f.get();
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getResponseBody(), "This is a simple test file\r\n");
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ZeroCopyHandler();
    }
}
