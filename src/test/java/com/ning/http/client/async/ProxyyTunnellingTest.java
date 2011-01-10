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

import com.ning.http.client.AsyncCompletionHandlerBase;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.SimpleAsyncHttpClient;
import com.ning.http.client.consumers.StringBufferBodyConsumer;
import com.ning.http.client.generators.InputStreamBodyGenerator;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ProxyHandler;
import org.testng.annotations.Test;
import sun.java2d.pipe.SpanShapeRenderer;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Proxy usage tests.
 */
public abstract class ProxyyTunnellingTest extends AbstractBasicTest {

    public AbstractHandler configureHandler() throws Exception {
        ProxyHandler proxy = new ProxyHandler();
        return proxy;
    }

    @Test(groups = {"online", "default_provider"})
    public void testRequestProxy() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        AsyncHttpClientConfig.Builder b = new AsyncHttpClientConfig.Builder();
        b.setFollowRedirects(true);

        ProxyServer ps = new ProxyServer(ProxyServer.Protocol.HTTPS, "127.0.0.1", port1);

        AsyncHttpClientConfig config = b.build();
        AsyncHttpClient asyncHttpClient = new AsyncHttpClient(config);

        RequestBuilder rb = new RequestBuilder("GET").setProxyServer(ps).setUrl("https://twitpic.com:443");
        Future<Response> responseFuture = asyncHttpClient.executeRequest(rb.build(), new AsyncCompletionHandlerBase() {

            public void onThrowable(Throwable t) {
                t.printStackTrace();
                log.debug(t.getMessage(), t);
            }

            @Override
            public Response onCompleted(Response response) throws Exception {
                return response;
            }
        });
        Response r = responseFuture.get();
        assertEquals(r.getStatusCode(), 200);
        assertEquals(r.getHeader("server"), "nginx");

        asyncHttpClient.close();
    }

    @Test(groups = {"online", "default_provider"})
    public void testConfigProxy() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        AsyncHttpClientConfig.Builder b = new AsyncHttpClientConfig.Builder();
        b.setFollowRedirects(true);

        ProxyServer ps = new ProxyServer(ProxyServer.Protocol.HTTPS, "127.0.0.1", port1);
        b.setProxyServer(ps);

        AsyncHttpClientConfig config = b.build();
        AsyncHttpClient asyncHttpClient = new AsyncHttpClient(config);

        RequestBuilder rb = new RequestBuilder("GET").setUrl("https://twitpic.com:443");
        Future<Response> responseFuture = asyncHttpClient.executeRequest(rb.build(), new AsyncCompletionHandlerBase() {

            public void onThrowable(Throwable t) {
                t.printStackTrace();
                log.debug(t.getMessage(), t);
            }

            @Override
            public Response onCompleted(Response response) throws Exception {
                return response;
            }
        });
        Response r = responseFuture.get();
        assertEquals(r.getStatusCode(), 200);
        assertEquals(r.getHeader("server"), "nginx");

        asyncHttpClient.close();
    }

    @Test(groups = {"online", "default_provider"})
    public void testSimpleAHCConfigProxy() throws IOException, InterruptedException, ExecutionException, TimeoutException {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
                .setProxyProtocol(ProxyServer.Protocol.HTTPS)
                .setProxyHost("127.0.0.1")
                .setProxyPort(port1)
                .setFollowRedirects(true)
                .setUrl("https://twitpic.com:443")
                .setHeader("Content-Type", "text/html").build();

        StringBuffer s = new StringBuffer();
        Response r = client.get().get();

        assertEquals(r.getStatusCode(), 200);
        assertEquals(r.getHeader("server"), "nginx");

        client.close();
    }
}

