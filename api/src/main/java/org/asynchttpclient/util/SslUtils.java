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
package org.asynchttpclient.util;

import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.asynchttpclient.AsyncHttpClientConfig;

/**
 * This class is a copy of http://github.com/sonatype/wagon-ning/raw/master/src/main/java/org/apache/maven/wagon/providers/http/SslUtils.java
 */
public class SslUtils {
    
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
        } catch (NoSuchAlgorithmException e) {
           throw new ExceptionInInitializerError(e);
        } catch (KeyManagementException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    
    private static class SingletonHolder {
        public static final SslUtils instance = new SslUtils();
    }

    public static SslUtils getInstance() {
        return SingletonHolder.instance;
    }

    public SSLContext getSSLContext(AsyncHttpClientConfig config) throws GeneralSecurityException {
        SSLContext sslContext = config.getSSLContext();

        if (sslContext == null) {
            sslContext = config.isAcceptAnyCertificate() ? looseTrustManagerSSLContext : SSLContext.getDefault();
            if (config.getSslSessionCacheSize() != null)
                sslContext.getClientSessionContext().setSessionCacheSize(config.getSslSessionCacheSize());
            if (config.getSslSessionTimeout() != null)
                sslContext.getClientSessionContext().setSessionTimeout(config.getSslSessionTimeout());
        }
        return sslContext;
    }
}
