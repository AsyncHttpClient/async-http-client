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
package org.asynchttpclient.proxy;

import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.*;
import static org.testng.Assert.assertEquals;
import io.netty.handler.codec.http.HttpHeaders;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.test.EchoHandler;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Proxy usage tests.
 */
public class HttpsProxyTest extends AbstractBasicTest {

    private Server server2;

    public AbstractHandler configureHandler() throws Exception {
        return new ConnectHandler();
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        port1 = findFreePort();
        server = newJettyHttpServer(port1);
        server.setHandler(configureHandler());
        server.start();

        port2 = findFreePort();

        server2 = newJettyHttpsServer(port2);
        server2.setHandler(new EchoHandler());
        server2.start();

        logger.info("Local HTTP server started successfully");
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        server.stop();
        server2.stop();
    }

    @Test(groups = "standalone")
    public void testRequestProxy() throws IOException, InterruptedException, ExecutionException, TimeoutException {

        try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config().setFollowRedirect(true).setAcceptAnyCertificate(true))) {
            RequestBuilder rb = get(getTargetUrl2()).setProxyServer(proxyServer("127.0.0.1", port1));
            Response r = asyncHttpClient.executeRequest(rb.build()).get();
            assertEquals(r.getStatusCode(), 200);
            assertEquals(r.getHeader("X-Connection"), HttpHeaders.Values.KEEP_ALIVE);
        }
    }

    @Test(groups = "standalone")
    public void testConfigProxy() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        AsyncHttpClientConfig config = config()//
                .setFollowRedirect(true)//
                .setProxyServer(proxyServer("127.0.0.1", port1).build())//
                .setAcceptAnyCertificate(true)//
                .build();
        try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config)) {
            Response r = asyncHttpClient.executeRequest(get(getTargetUrl2())).get();
            assertEquals(r.getStatusCode(), 200);
            assertEquals(r.getHeader("X-Connection"), HttpHeaders.Values.KEEP_ALIVE);
        }
    }

    @Test(groups = "standalone")
    public void testPooledConnectionsWithProxy() throws IOException, InterruptedException, ExecutionException, TimeoutException {

        try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config().setFollowRedirect(true).setAcceptAnyCertificate(true).setKeepAlive(true))) {
            RequestBuilder rb = get(getTargetUrl2()).setProxyServer(proxyServer("127.0.0.1", port1));

            Response r1 = asyncHttpClient.executeRequest(rb.build()).get();
            assertEquals(r1.getStatusCode(), 200);

            Response r2 = asyncHttpClient.executeRequest(rb.build()).get();
            assertEquals(r2.getStatusCode(), 200);
        }
    }
}
