/*
 * Copyright 2010-2013 Ning, Inc.
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
 *
 */
package org.asynchttpclient.async;

import static org.testng.Assert.assertNotNull;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Response;
import org.testng.annotations.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public abstract class NoNullResponseTest extends AbstractBasicTest {
    private static final String GOOGLE_HTTPS_URL = "https://www.google.com";

    @Test(invocationCount = 4, groups = { "online", "default_provider" })
    public void multipleSslRequestsWithDelayAndKeepAlive() throws Exception {
        final AsyncHttpClient client = create();
        try {
            final BoundRequestBuilder builder = client.prepareGet(GOOGLE_HTTPS_URL);
            final Response response1 = builder.execute().get();
            Thread.sleep(4000);
            final Response response2 = builder.execute().get();
            if (response2 != null) {
                System.out.println("Success (2nd response was not null).");
            } else {
                System.out.println("Failed (2nd response was null).");
            }
            assertNotNull(response1);
            assertNotNull(response2);
        } finally {
            client.close();
        }
    }

    private AsyncHttpClient create() throws GeneralSecurityException {
        final AsyncHttpClientConfig.Builder configBuilder = new AsyncHttpClientConfig.Builder().setFollowRedirect(true).setSSLContext(getSSLContext()).setAllowPoolingConnections(true).setConnectionTimeout(10000)
                .setPooledConnectionIdleTimeout(60000).setRequestTimeout(10000).setMaxConnectionsPerHost(-1).setMaxConnections(-1);
        return getAsyncHttpClient(configBuilder.build());
    }

    private SSLContext getSSLContext() throws GeneralSecurityException {
        final SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { new MockTrustManager() }, null);
        return sslContext;
    }

    private static class MockTrustManager implements X509TrustManager {
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }

        public void checkClientTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
            // do nothing.
        }

        public void checkServerTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
            // Do nothing.
        }
    }
}
