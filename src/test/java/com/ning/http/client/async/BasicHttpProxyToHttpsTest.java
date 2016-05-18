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
package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Realm.AuthScheme;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Test that validates that when having an HTTP proxy and trying to access an HTTPS through the proxy the
 * proxy credentials should be passed during the CONNECT request.
 */
public abstract class BasicHttpProxyToHttpsTest extends AbstractBasicTest {

    private Server server2;

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        server.stop();
        server2.stop();
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        // HTTP Proxy Server
        server = new Server();
        // HTTPS Server
        server2 = new Server();

        port1 = findFreePort();
        port2 = findFreePort();

        // Proxy Server configuration
        Connector listener = new SelectChannelConnector();
        listener.setHost("127.0.0.1");
        listener.setPort(port1);
        server.addConnector(listener);
        server.setHandler(configureHandler());
        server.start();

        // HTTPS Server
        SslSocketConnector connector = new SslSocketConnector();
        connector.setHost("127.0.0.1");
        connector.setPort(port2);

        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        String keyStoreFile = new File(keystoreUrl.toURI()).getAbsolutePath();
        connector.setKeystore(keyStoreFile);
        connector.setKeyPassword("changeit");
        connector.setKeystoreType("JKS");

        log.info("SSL keystore path: {}", keyStoreFile);

        server2.addConnector(connector);
        server2.setHandler(new EchoHandler());
        server2.start();
        log.info("Local Proxy Server (" + port1 + "), HTTPS Server (" + port2 + ") started successfully");
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ConnectHandler(new EchoHandler()) {
            @Override
            protected boolean handleAuthentication(HttpServletRequest request, HttpServletResponse response, String address) throws ServletException, IOException {
                String authorization = request.getHeader("Proxy-Authorization");
                if (authorization == null) {
                    response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
                    response.setHeader("Proxy-Authenticate", "Basic realm=\"Fake Realm\"");
                    return false;
                } else if (authorization
                        .equals("Basic am9obmRvZTpwYXNz")) {
                    return true;
                }
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
        };
    }

    @Test
    public void httpProxyToHttpsTargetTest() throws IOException, InterruptedException, ExecutionException, NoSuchAlgorithmException {
        try (AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setAcceptAnyCertificate(true).build())) {
            Request request = new RequestBuilder("GET").setProxyServer(basicProxy()).setUrl(getTargetUrl2()).setRealm(new Realm.RealmBuilder().setPrincipal("user").setPassword("passwd").build()).build();
            Future<Response> responseFuture = client.executeRequest(request);
            Response response = responseFuture.get();
            Assert.assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
            Assert.assertEquals("127.0.0.1:" + port2, response.getHeader("x-host"));
        }
    }

    private ProxyServer basicProxy() throws UnknownHostException {
        ProxyServer proxyServer = new ProxyServer("127.0.0.1", port1, "johndoe", "pass");
        proxyServer.setScheme(AuthScheme.BASIC);
        return proxyServer;
    }
}
