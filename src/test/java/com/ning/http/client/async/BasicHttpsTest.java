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
import com.ning.http.client.AsyncHttpClientConfig.Builder;
import com.ning.http.client.Response;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public abstract class BasicHttpsTest extends AbstractBasicTest {

    protected final Logger log = LoggerFactory.getLogger(BasicHttpsTest.class);

    public static class EchoHandler extends AbstractHandler {

        public void handle(String pathInContext, Request r, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException, IOException {

            httpResponse.setContentType("text/html; charset=utf-8");
            Enumeration<?> e = httpRequest.getHeaderNames();
            String param;
            while (e.hasMoreElements()) {
                param = e.nextElement().toString();

                if (param.startsWith("LockThread")) {
                    try {
                        Thread.sleep(40 * 1000);
                    } catch (InterruptedException ex) { // nothing to do here
                    }
                }

                httpResponse.addHeader("X-" + param, httpRequest.getHeader(param));
            }

            Enumeration<?> i = httpRequest.getParameterNames();

            StringBuilder requestBody = new StringBuilder();
            while (i.hasMoreElements()) {
                param = i.nextElement().toString();
                httpResponse.addHeader("X-" + param, httpRequest.getParameter(param));
                requestBody.append(param);
                requestBody.append("_");
            }

            String pathInfo = httpRequest.getPathInfo();
            if (pathInfo != null)
                httpResponse.addHeader("X-pathInfo", pathInfo);

            String queryString = httpRequest.getQueryString();
            if (queryString != null)
                httpResponse.addHeader("X-queryString", queryString);

            httpResponse.addHeader("X-KEEP-ALIVE", httpRequest.getRemoteAddr() + ":" + httpRequest.getRemotePort());

            javax.servlet.http.Cookie[] cs = httpRequest.getCookies();
            if (cs != null) {
                for (javax.servlet.http.Cookie c : cs) {
                    httpResponse.addCookie(c);
                }
            }

            if (requestBody.length() > 0) {
                httpResponse.getOutputStream().write(requestBody.toString().getBytes());
            }

            int size = 10 * 1024;
            if (httpRequest.getContentLength() > 0) {
                size = httpRequest.getContentLength();
            }
            byte[] bytes = new byte[size];
            int pos = 0;
            if (bytes.length > 0) {
                // noinspection ResultOfMethodCallIgnored
                int read = 0;
                while (read != -1) {
                    read = httpRequest.getInputStream().read(bytes, pos, bytes.length - pos);
                    pos += read;
                }

                httpResponse.getOutputStream().write(bytes, 0, pos + 1); // (pos + 1) because last read added -1
            }

            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();

        }
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        server.stop();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDownProps() throws Exception {
        System.clearProperty("javax.net.ssl.keyStore");
        System.clearProperty("javax.net.ssl.trustStore");
    }

    protected String getTargetUrl() {
        return String.format("https://127.0.0.1:%d/foo/test", port1);
    }

    public AbstractHandler configureHandler() throws Exception {
        return new EchoHandler();
    }

    protected int findFreePort() throws IOException {
        ServerSocket socket = null;

        try {
            socket = new ServerSocket(0);

            return socket.getLocalPort();
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        server = new Server();
        port1 = findFreePort();
        SslSocketConnector connector = new SslSocketConnector();
        connector.setHost("127.0.0.1");
        connector.setPort(port1);

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

        server.setHandler(configureHandler());
        server.start();
        log.info("Local HTTP server started successfully");
    }

    @Test(groups = { "standalone", "default_provider" })
    public void zeroCopyPostTest() throws Throwable {
        final AsyncHttpClient client = getAsyncHttpClient(new Builder().setSSLContext(createSSLContext(new AtomicBoolean(true))).build());
        try {
            ClassLoader cl = getClass().getClassLoader();
            // override system properties
            URL url = cl.getResource("SimpleTextFile.txt");
            File file = new File(url.toURI());

            Future<Response> f = client.preparePost(getTargetUrl()).setBody(file).setHeader("Content-Type", "text/html").execute();
            Response resp = f.get();
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), "This is a simple test file");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void multipleSSLRequestsTest() throws Throwable {
        final AsyncHttpClient c = getAsyncHttpClient(new Builder().setSSLContext(createSSLContext(new AtomicBoolean(true))).build());
        try {
            String body = "hello there";

            // once
            Response response = c.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute().get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(response.getResponseBody(), body);

            // twice
            response = c.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute().get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(response.getResponseBody(), body);
        } finally {
            c.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void multipleSSLWithoutCacheTest() throws Throwable {
        final AsyncHttpClient client = getAsyncHttpClient(new Builder().setSSLContext(createSSLContext(new AtomicBoolean(true))).setAllowPoolingSslConnections(false).build());
        try {
            String body = "hello there";
            client.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute();

            client.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute();

            Response response = client.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute().get();

            assertEquals(response.getResponseBody(), body);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void reconnectsAfterFailedCertificationPath() throws Exception {
        
        AtomicBoolean trust = new AtomicBoolean(false);
        AsyncHttpClient client = getAsyncHttpClient(new Builder().setSSLContext(createSSLContext(trust)).build());
        try {
            String body = "hello there";

            // first request fails because server certificate is rejected
            Throwable cause = null;
            try {
                client.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute().get(TIMEOUT, TimeUnit.SECONDS);
            } catch (final ExecutionException e) {
                cause = e.getCause();
                if (cause instanceof ConnectException) {
                    //assertNotNull(cause.getCause());
                    assertTrue(cause.getCause() instanceof SSLHandshakeException, "Expected an SSLHandshakeException, got a " + cause.getCause());
                } else {
                   assertTrue(cause instanceof IOException, "Expected an IOException, got a " + cause);
                }
            }
            assertNotNull(cause);

            // second request should succeed
            trust.set(true);
            Response response = client.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute().get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(response.getResponseBody(), body);
        } finally {
            client.close();
        }
    }

    @Test(timeOut = 5000)
    public void failInstantlyIfHostNamesDiffer() throws Exception {

        HostnameVerifier hostnameVerifier = new HostnameVerifier() {
            public boolean verify(String arg0, SSLSession arg1) {
                return false;
            }
        };

        AsyncHttpClient client = getAsyncHttpClient(new Builder().setHostnameVerifier(hostnameVerifier).setRequestTimeout(20000).build());

        try {
            try {
            client.prepareGet("https://github.com/AsyncHttpClient/async-http-client/issues/355").execute().get(TIMEOUT, TimeUnit.SECONDS);
            
            Assert.assertTrue(false, "Shouldn't be here: should get an Exception");
            } catch (ExecutionException e) {
                Assert.assertTrue(e.getCause() instanceof ConnectException, "Cause should be a ConnectException");
            } catch (Exception e) {
                Assert.assertTrue(false, "Shouldn't be here: should get a ConnectException wrapping a ConnectException");
            }
            
        } finally {
            client.close();
        }
    }

    private static KeyManager[] createKeyManagers() throws GeneralSecurityException, IOException {
        InputStream keyStoreStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("ssltest-cacerts.jks");
        char[] keyStorePassword = "changeit".toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(keyStoreStream, keyStorePassword);
        assert(ks.size() > 0);

        // Set up key manager factory to use our key store
        char[] certificatePassword = "changeit".toCharArray();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, certificatePassword);

        // Initialize the SSLContext to work with our key managers.
        return kmf.getKeyManagers();
    }

    private static TrustManager[] createTrustManagers() throws GeneralSecurityException, IOException {
        InputStream keyStoreStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("ssltest-keystore.jks");
        char[] keyStorePassword = "changeit".toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(keyStoreStream, keyStorePassword);
        assert(ks.size() > 0);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return tmf.getTrustManagers();
    }

    public static SSLContext createSSLContext(AtomicBoolean trust) {
        try {
            KeyManager[] keyManagers = createKeyManagers();
            TrustManager[] trustManagers = new TrustManager[] { dummyTrustManager(trust, (X509TrustManager) createTrustManagers()[0]) };
            SecureRandom secureRandom = new SecureRandom();

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, trustManagers, secureRandom);

            return sslContext;
        } catch (Exception e) {
            throw new Error("Failed to initialize the server-side SSLContext", e);
        }
    }

    public static class DummyTrustManager implements X509TrustManager {

        private final X509TrustManager tm;
        private final AtomicBoolean trust;

        public DummyTrustManager(final AtomicBoolean trust, final X509TrustManager tm) {
            this.trust = trust;
            this.tm = tm;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            tm.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (!trust.get()) {
                throw new CertificateException("Server certificate not trusted.");
            }
            tm.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return tm.getAcceptedIssuers();
        }
    }

    private static TrustManager dummyTrustManager(final AtomicBoolean trust, final X509TrustManager tm) {
        return new DummyTrustManager(trust, tm);
    }
}
