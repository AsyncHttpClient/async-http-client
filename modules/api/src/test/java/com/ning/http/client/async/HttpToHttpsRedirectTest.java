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
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public abstract class HttpToHttpsRedirectTest extends AbstractBasicTest {
    private final AtomicBoolean isSet = new AtomicBoolean(false);

    private class Relative302Handler extends AbstractHandler {


        public void handle(String s,
                           Request r,
                           HttpServletRequest httpRequest,
                           HttpServletResponse httpResponse) throws IOException, ServletException {

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

            if (r.getScheme().equalsIgnoreCase("https")) {
                httpResponse.addHeader("X-httpToHttps", "PASS");
                isSet.getAndSet(false);
            }

            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();
        }
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        server = new Server();

        port1 = findFreePort();
        port2 = findFreePort();

        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(port1);
        server.addConnector(listener);

        SslSocketConnector connector = new SslSocketConnector();
        connector.setHost("127.0.0.1");
        connector.setPort(port2);

        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL cacertsUrl = cl.getResource("ssltest-cacerts.jks");
        String trustStoreFile = new File(cacertsUrl.toURI()).getAbsolutePath();
        connector.setTruststore(trustStoreFile);
        connector.setTrustPassword("changeit");
        connector.setTruststoreType("JKS");

        log.info("SSL certs path: {}", trustStoreFile);

        // override system properties
        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        String keyStoreFile = new File(keystoreUrl.toURI()).getAbsolutePath();
        connector.setKeystore(keyStoreFile);
        connector.setKeyPassword("changeit");
        connector.setKeystoreType("JKS");

        log.info("SSL keystore path: {}", keyStoreFile);

        server.addConnector(connector);

        server.setHandler(new Relative302Handler());
        server.start();
        log.info("Local HTTP server started successfully");
    }

    private String getBaseUrl(URI uri) {
        String url = uri.toString();
        int port = uri.getPort();
        if (port == -1) {
            port = getPort(uri);
            url = url.substring(0, url.length() - 1) + ":" + port;
        }
        return url.substring(0, url.lastIndexOf(":") + String.valueOf(port).length() + 1);
    }

    private static int getPort(URI uri) {
        int port = uri.getPort();
        if (port == -1)
            port = uri.getScheme().equals("http") ? 80 : 443;
        return port;
    }

    @Test(groups = {"standalone", "default_provider"})
    public void httpToHttpsRedirect() throws Throwable {
        isSet.getAndSet(false);

        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setMaximumNumberOfRedirects(5).setFollowRedirects(true).build();
        AsyncHttpClient c = getAsyncHttpClient(cg);

        Response response = c.prepareGet(getTargetUrl())
                .setHeader("X-redirect", getTargetUrl2())
                .execute().get();
        assertNotNull(response);
        assertEquals(response.getStatusCode(), 200);
        assertEquals(response.getHeader("X-httpToHttps"), "PASS");
        c.close();
    }

    public String getTargetUrl2() {
        return String.format("https://127.0.0.1:%d/foo/test", port2);
    }

    @Test(groups = {"standalone", "default_provider"})
    public void httpToHttpsProperConfig() throws Throwable {
        isSet.getAndSet(false);

        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setMaximumNumberOfRedirects(5).setFollowRedirects(true).build();
        AsyncHttpClient c = getAsyncHttpClient(cg);

        Response response = c.prepareGet(getTargetUrl())
                .setHeader("X-redirect", getTargetUrl2() + "/test2")
                .execute().get();
        assertNotNull(response);
        assertEquals(response.getStatusCode(), 200);
        assertEquals(response.getHeader("X-httpToHttps"), "PASS");

        // Test if the internal channel is downgraded to clean http.
        response = c.prepareGet(getTargetUrl())
                .setHeader("X-redirect", getTargetUrl2() + "/foo2")
                .execute().get();
        assertNotNull(response);
        assertEquals(response.getStatusCode(), 200);
        assertEquals(response.getHeader("X-httpToHttps"), "PASS");
        c.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void relativeLocationUrl() throws Throwable {
        isSet.getAndSet(false);

        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setMaximumNumberOfRedirects(5).setFollowRedirects(true).build();
        AsyncHttpClient c = getAsyncHttpClient(cg);

        Response response = c.prepareGet(getTargetUrl())
                .setHeader("X-redirect", "/foo/test")
                .execute().get();
        assertNotNull(response);
        assertEquals(response.getStatusCode(), 302);
        assertEquals(response.getUri().toString(), getTargetUrl());
        c.close();
    }
}
