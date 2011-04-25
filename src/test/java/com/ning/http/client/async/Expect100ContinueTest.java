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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.Future;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Test the Expect: 100-Continue.
 */
public abstract class Expect100ContinueTest extends AbstractBasicTest {

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
                final int read = httpRequest.getInputStream().read(bytes);
                httpResponse.getOutputStream().write(bytes, 0, read);
            }

            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
        }
    }

    @Test(groups = {"standalone", "default_provider"})
    public void Expect100Continue() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);

        ClassLoader cl = getClass().getClassLoader();
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());

        Future<Response> f = client.preparePut("http://127.0.0.1:" + port1 + "/").setHeader("Expect", "100-continue").setBody(file).execute();
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

}
