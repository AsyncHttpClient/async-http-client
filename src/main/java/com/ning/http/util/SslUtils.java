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
package com.ning.http.util;

import com.ning.http.client.AsyncHttpClientConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

public class SslUtils {

    private static class SingletonHolder {
        public static final SslUtils instance = new SslUtils();
    }

    public static SslUtils getInstance() {
        return SingletonHolder.instance;
    }

    public SSLEngine createClientSSLEngine(AsyncHttpClientConfig config, String peerHost, int peerPort) throws GeneralSecurityException, IOException {
        SSLContext sslContext = config.getSSLContext();
        if (sslContext == null) {
            sslContext = SslUtils.getInstance().getSSLContext(config.isAcceptAnyCertificate());
        }
        SSLEngine sslEngine = sslContext.createSSLEngine(peerHost, peerPort);
        sslEngine.setUseClientMode(true);
        return sslEngine;
    }
    
    public SSLContext getSSLContext(boolean acceptAnyCertificate) throws GeneralSecurityException, IOException {
        // SSLContext.getDefault() doesn't exist in JDK5
        return acceptAnyCertificate ? looseTrustManagerSSLContext : SSLContext.getInstance("Default");
    }

    static class LooseTrustManager implements X509TrustManager {

        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }

        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
        }

        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
        }
    }

    private SSLContext looseTrustManagerSSLContext = looseTrustManagerSSLContext();

    private SSLContext looseTrustManagerSSLContext() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { new LooseTrustManager() }, new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
