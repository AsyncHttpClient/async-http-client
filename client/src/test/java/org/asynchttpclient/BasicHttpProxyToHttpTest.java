/*
 * Copyright (c) 2016 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient;

import static io.netty.handler.codec.http.HttpHeaders.Names.*;
import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.*;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.test.EchoHandler;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Test that validates that when having an HTTP proxy and trying to access an HTTP through the proxy the proxy credentials should be passed after it gets a 407 response.
 */
public class BasicHttpProxyToHttpTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(BasicHttpProxyToHttpTest.class);

    private int httpPort;
    private int proxyPort;

    private Server httpServer;
    private Server proxy;

    @SuppressWarnings("serial")
    public static class BasicAuthProxyServlet extends ProxyServlet {

        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
            LOGGER.debug(">>> got a request !");

            HttpServletRequest httpReq = (HttpServletRequest) req;
            HttpServletResponse httpRes = (HttpServletResponse) res;

            String authorization = httpReq.getHeader(PROXY_AUTHORIZATION);
            if (authorization == null) {
                httpRes.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
                httpRes.setHeader(PROXY_AUTHENTICATE, "Basic realm=\"Fake Realm\"");
                httpRes.getOutputStream().flush();

            } else if (authorization.equals("Basic am9obmRvZTpwYXNz")) {
                super.service(req, res);

            } else {
                httpRes.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                httpRes.getOutputStream().flush();
            }
        }
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {

        httpPort = findFreePort();
        proxyPort = findFreePort();

        httpServer = newJettyHttpServer(httpPort);
        httpServer.setHandler(new EchoHandler());
        httpServer.start();

        proxy = new Server(proxyPort);
        ServletHandler servletHandler = new ServletHandler();
        ServletHolder servletHolder = servletHandler.addServletWithMapping(BasicAuthProxyServlet.class, "/*");
        servletHolder.setInitParameter("maxThreads", "5");
        proxy.setHandler(servletHandler);
        proxy.start();

        LOGGER.info("Local HTTP Server (" + httpPort + "), Proxy (" + proxyPort + ") started successfully");
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        httpServer.stop();
        proxy.stop();
    }

    @Test
    public void nonPreemptyProxyAuthWithPlainHttpTarget() throws IOException, InterruptedException, ExecutionException {
        try (AsyncHttpClient client = asyncHttpClient()) {
            String targetUrl = "http://localhost:" + httpPort + "/foo/bar";
            Request request = get(targetUrl)//
                    .setProxyServer(proxyServer("127.0.0.1", proxyPort).setRealm(realm(AuthScheme.BASIC, "johndoe", "pass")))//
                    //.setRealm(realm(AuthScheme.BASIC, "user", "passwd"))//
                    .build();
            Future<Response> responseFuture = client.executeRequest(request);
            Response response = responseFuture.get();

            Assert.assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
            Assert.assertEquals("/foo/bar", response.getHeader("X-pathInfo"));
        }
    }
}