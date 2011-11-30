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
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public abstract class InputStreamTest extends AbstractBasicTest {

    private class InputStreamHandler extends AbstractHandler {
        public void handle(String s,
                           Request r,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException {
            if ("POST".equalsIgnoreCase(request.getMethod())) {
                byte[] b = new byte[3];
                request.getInputStream().read(b, 0, 3);

                response.setStatus(HttpServletResponse.SC_OK);
                response.addHeader("X-Param", new String(b));
            } else { // this handler is to handle POST request
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }

    @Test(groups = {"standalone", "default_provider"})
    public void testInvalidInputStream() throws IOException, ExecutionException, TimeoutException, InterruptedException {

        AsyncHttpClient c = getAsyncHttpClient(null);
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        h.add("Content-Type", "application/x-www-form-urlencoded");

        InputStream is = new InputStream() {

            public int readAllowed;

            @Override
            public int available() {
                return 1; // Fake
            }

            @Override
            public int read() throws IOException {
                int fakeCount = readAllowed++;
                if (fakeCount == 0) {
                    return (int) 'a';
                } else if (fakeCount == 1) {
                    return (int) 'b';
                } else if (fakeCount == 2) {
                    return (int) 'c';
                } else {
                    return -1;
                }

            }
        };

        Response resp = c.preparePost(getTargetUrl()).setHeaders(h).setBody(is).execute().get();
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getHeader("X-Param"), "abc");
        c.close();
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new InputStreamHandler();
    }
}
