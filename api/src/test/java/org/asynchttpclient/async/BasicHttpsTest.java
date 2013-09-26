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
package org.asynchttpclient.async;

import static org.asynchttpclient.async.util.TestUtils.*;
import static org.testng.Assert.*;

import java.net.ConnectException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLHandshakeException;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig.Builder;
import org.asynchttpclient.Response;
import org.testng.annotations.Test;

public abstract class BasicHttpsTest extends AbstractBasicHttpsTest {

    protected String getTargetUrl() {
        return String.format("https://127.0.0.1:%d/foo/test", port1);
    }

    @Test(groups = { "standalone", "default_provider" })
    public void zeroCopyPostTest() throws Exception {

        final AsyncHttpClient client = getAsyncHttpClient(new Builder().setSSLContext(createSSLContext(new AtomicBoolean(true))).build());
        try {
            Response resp = client.preparePost(getTargetUrl()).setBody(SIMPLE_TEXT_FILE).setHeader("Content-Type", "text/html").execute().get();
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void multipleSSLRequestsTest() throws Exception {
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
    public void multipleSSLWithoutCacheTest() throws Exception {
        AsyncHttpClient c = getAsyncHttpClient(new Builder().setSSLContext(createSSLContext(new AtomicBoolean(true))).setAllowSslConnectionPool(false).build());
        try {
            String body = "hello there";
            c.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute();

            c.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute();

            Response response = c.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute().get();

            assertEquals(response.getResponseBody(), body);
        } finally {
            c.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void reconnectsAfterFailedCertificationPath() throws Exception {
        AtomicBoolean trusted = new AtomicBoolean(false);
        AsyncHttpClient c = getAsyncHttpClient(new Builder().setSSLContext(createSSLContext(trusted)).build());
        try {
            String body = "hello there";

            // first request fails because server certificate is rejected
            try {
                c.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute().get(TIMEOUT, TimeUnit.SECONDS);
            } catch (final ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof ConnectException) {
                    assertNotNull(cause.getCause());
                    assertTrue(cause.getCause() instanceof SSLHandshakeException, "Expected an SSLHandshakeException, got a " + cause.getCause());
                } else {
                    assertTrue(cause instanceof SSLHandshakeException, "Expected an SSLHandshakeException, got a " + cause);
                }
            }

            trusted.set(true);

            // second request should succeed
            Response response = c.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute().get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(response.getResponseBody(), body);
        } finally {
            c.close();
        }
    }
}
