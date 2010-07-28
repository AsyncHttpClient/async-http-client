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
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Proxy usage tests.
 *
 * @author Hubert Iwaniuk
 */
public class ProxyTest extends AbstractBasicTest {
    private class ProxyHandler extends AbstractHandler {
        public void handle(String s,
                           Request r,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                response.addHeader("target", r.getUri().toString());
                response.setStatus(HttpServletResponse.SC_OK);
            } else { // this handler is to handle POST request
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
            r.setHandled(true);
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ProxyHandler();
    }

    @Test(groups = "standalone")
    public void testRequestLevelProxy() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = new AsyncHttpClient();
        String target = "http://127.0.0.1:1234/";
        Future<Response> f = client
                .prepareGet(target)
                .setProxyServer(new ProxyServer("127.0.0.1", port1))
                .execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getHeader("target"), target);
    }

    @Test(groups = "standalone")
    public void testGlobalProxy() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClientConfig cfg
                = new AsyncHttpClientConfig.Builder().setProxyServer(new ProxyServer("127.0.0.1", port1)).build();
        AsyncHttpClient client = new AsyncHttpClient(cfg);
        String target = "http://127.0.0.1:1234/";
        Future<Response> f = client
                .prepareGet(target)
                .execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getHeader("target"), target);
    }

    @Test(groups = "standalone")
    public void testBothProxies() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClientConfig cfg
                = new AsyncHttpClientConfig.Builder().setProxyServer(new ProxyServer("127.0.0.1", port1 - 1)).build();
        AsyncHttpClient client = new AsyncHttpClient(cfg);
        String target = "http://127.0.0.1:1234/";
        Future<Response> f = client
                .prepareGet(target)
                .setProxyServer(new ProxyServer("127.0.0.1", port1))
                .execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getHeader("target"), target);
    }
}
