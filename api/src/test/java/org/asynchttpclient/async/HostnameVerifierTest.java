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
package org.asynchttpclient.async;

import static org.asynchttpclient.async.util.TestUtils.*;
import static org.testng.Assert.*;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig.Builder;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

public abstract class HostnameVerifierTest extends AbstractBasicHttpsTest {

    protected final Logger log = LoggerFactory.getLogger(HostnameVerifierTest.class);

    public static class EchoHandler extends AbstractHandler {

        @Override
        public void handle(String pathInContext, Request r, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException, IOException {

            httpResponse.setContentType(TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
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
                int read = 0;
                while (read != -1) {
                    read = httpRequest.getInputStream().read(bytes, pos, bytes.length - pos);
                    pos += read;
                }

                httpResponse.getOutputStream().write(bytes);
            }

            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();

        }
    }

    protected String getTargetUrl() {
        return String.format("https://127.0.0.1:%d/foo/test", port1);
    }

    public AbstractHandler configureHandler() throws Exception {
        return new EchoHandler();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void positiveHostnameVerifierTest() throws Exception {

        final AsyncHttpClient client = getAsyncHttpClient(new Builder().setHostnameVerifier(new PositiveHostVerifier()).setSSLContext(createSSLContext(new AtomicBoolean(true))).build());
        try {
            Future<Response> f = client.preparePost(getTargetUrl()).setBody(SIMPLE_TEXT_FILE).setHeader("Content-Type", "text/html").execute();
            Response resp = f.get();
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void negativeHostnameVerifierTest() throws Exception {

        final AsyncHttpClient client = getAsyncHttpClient(new Builder().setHostnameVerifier(new NegativeHostVerifier()).setSSLContext(createSSLContext(new AtomicBoolean(true))).build());
        try {
            try {
                client.preparePost(getTargetUrl()).setBody(SIMPLE_TEXT_FILE).setHeader("Content-Type", "text/html").execute().get();
                fail("ConnectException expected");
            } catch (ExecutionException ex) {
                assertEquals(ex.getCause().getClass(), ConnectException.class);
            }
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void remoteIDHostnameVerifierTest() throws Exception {

        final AsyncHttpClient client = getAsyncHttpClient(new Builder().setHostnameVerifier(new CheckHost("bouette")).setSSLContext(createSSLContext(new AtomicBoolean(true))).build());
        try {
            client.preparePost(getTargetUrl()).setBody(SIMPLE_TEXT_FILE).setHeader("Content-Type", "text/html").execute().get();
            fail("ConnectException expected");
        } catch (ExecutionException ex) {
            assertEquals(ex.getCause().getClass(), ConnectException.class);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void remoteNegHostnameVerifierTest() throws Exception {
        // request is made to 127.0.0.1, but cert presented for localhost - this should fail
        final AsyncHttpClient client = getAsyncHttpClient(new Builder().setHostnameVerifier(new CheckHost("localhost")).setSSLContext(createSSLContext(new AtomicBoolean(true))).build());
        try {
            client.preparePost(getTargetUrl()).setBody(SIMPLE_TEXT_FILE).setHeader("Content-Type", "text/html").execute().get();
            fail("ConnectException expected");
        } catch (ExecutionException ex) {
            assertEquals(ex.getCause().getClass(), ConnectException.class);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void remotePosHostnameVerifierTest() throws Exception {

        final AsyncHttpClient client = getAsyncHttpClient(new Builder().setHostnameVerifier(new CheckHost("127.0.0.1")).setSSLContext(createSSLContext(new AtomicBoolean(true))).build());
        try {
            Response resp = client.preparePost(getTargetUrl()).setBody(SIMPLE_TEXT_FILE).setHeader("Content-Type", "text/html").execute().get();
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        } finally {
            client.close();
        }
    }

    public static class PositiveHostVerifier implements HostnameVerifier {

        public boolean verify(String s, SSLSession sslSession) {
            return true;
        }
    }

    public static class NegativeHostVerifier implements HostnameVerifier {

        public boolean verify(String s, SSLSession sslSession) {
            return false;
        }
    }

    public static class CheckHost implements HostnameVerifier {

        private final String hostName;

        public CheckHost(String hostName) {
            this.hostName = hostName;
        }

        public boolean verify(String s, SSLSession sslSession) {
            return s != null && s.equalsIgnoreCase(hostName);
        }
    }
}
