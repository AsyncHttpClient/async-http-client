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
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Response;
import org.apache.log4j.BasicConfigurator;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.AbstractHandler;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ConnectException;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;


public class Relative302Test extends AbstractBasicTest {
    private final AtomicBoolean isSet = new AtomicBoolean(false);

    private class Relative302Handler extends AbstractHandler {


        public void handle(String s,
                           HttpServletRequest httpRequest,
                           HttpServletResponse httpResponse,
                           int i) throws IOException, ServletException {

                String param;
                httpResponse.setContentType("text/html; charset=utf-8");
                Enumeration<?> e = httpRequest.getHeaderNames();
                while (e.hasMoreElements()) {
                    param = e.nextElement().toString();

                    if (param.startsWith("X-redirect") && !isSet.getAndSet(true)) {
                        httpResponse.addHeader("Location", httpRequest.getHeader(param));
                        httpResponse.setStatus(302);
                        httpResponse.getOutputStream().flush();
                        httpResponse.getOutputStream().close();
                        return;
                    }
                }
                httpResponse.setStatus(200);
                httpResponse.getOutputStream().flush();
                httpResponse.getOutputStream().close();
        }
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        server = new Server();
        BasicConfigurator.configure();

        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(PORT);
        server.addConnector(listener);

        server.setHandler(new Relative302Handler());
        server.start();
        log.info("Local HTTP server started successfully");
    }

    @Test
    public void redirected302Test() throws Throwable {
        isSet.getAndSet(false);
        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build();
        AsyncHttpClient c = new AsyncHttpClient(cg);

        // once
        Response response = c.prepareGet(TARGET_URL)
                .setHeader("X-redirect", "http://www.microsoft.com/")
                .execute().get();

        assertNotNull(response);
        assertEquals(response.getStatusCode(),200);
        assertEquals(response.getUrl().getBaseUrl(), "http://www.microsoft.com");
    }

    @Test
    public void redirected302InvalidTest() throws Throwable {
        isSet.getAndSet(false);
        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build();
        AsyncHttpClient c = new AsyncHttpClient(cg);

        // If the test hit a proxy, no ConnectException will be thrown and instead of 404 will be returned.
        try {
            Response response = c.preparePost(TARGET_URL)
                    .setHeader("X-redirect", "http://www.grroooogle.com/")
                    .execute().get();

            assertNotNull(response);
            assertEquals(response.getStatusCode(),404);
        } catch (ExecutionException ex) {
            assertEquals(ex.getCause().getClass(), ConnectException.class);
        }
    }

    @Test
    public void relativeLocationUrl() throws Throwable {
        isSet.getAndSet(false);
        
        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build();
        AsyncHttpClient c = new AsyncHttpClient(cg);

        Response response = c.preparePost(TARGET_URL)
                .setHeader("X-redirect", "/foo/test")
                .execute().get();
        assertNotNull(response);
        assertEquals(response.getStatusCode(),200);
        assertEquals(response.getUrl().toString(), TARGET_URL);
    }
}
