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
 *
 */
package com.ning.http.client.async;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;

/**
 * Tests to reproduce issues with handling of error responses
 * 
 * @author Tatu Saloranta
 */
public abstract class ErrorResponseTest extends AbstractBasicTest {
    final static String BAD_REQUEST_STR = "Very Bad Request! No cookies.";

    private static class ErrorHandler extends AbstractHandler {
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            try {
                Thread.sleep(210L);
            } catch (InterruptedException e) {
            }
            response.setContentType("text/plain");
            response.setStatus(400);
            OutputStream out = response.getOutputStream();
            out.write(BAD_REQUEST_STR.getBytes("UTF-8"));
            out.flush();
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ErrorHandler();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testQueryParameters() throws Exception {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Future<Response> f = client.prepareGet("http://127.0.0.1:" + port1 + "/foo").addHeader("Accepts", "*/*").execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), 400);
            String respStr = resp.getResponseBody();
            assertEquals(BAD_REQUEST_STR, respStr);
        } finally {
            client.close();
        }
    }
}
