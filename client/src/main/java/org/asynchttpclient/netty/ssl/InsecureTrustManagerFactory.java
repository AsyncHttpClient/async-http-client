/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.netty.ssl;

import io.netty.handler.ssl.util.SimpleTrustManagerFactory;
import io.netty.util.internal.EmptyArrays;

import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//TODO: Replace this with Netty's InsecureTrustManager once it creates X509ExtendedTrustManager.
//
// When a server mandates the authentication of a client certificate, JDK internally wraps a TrustManager
// with AbstractTrustManagerWrapper unless it extends X509ExtendedTrustManager. AbstractTrustManagerWrapper
// performs an additional check (DN comparison), making InsecureTrustManager not insecure enough.
//
// To work around this problem, we forked Netty's InsecureTrustManagerFactory and made its TrustManager
// implementation extend X509ExtendedTrustManager instead of X509TrustManager.
// see https://github.com/netty/netty/issues/5910
public final class InsecureTrustManagerFactory extends SimpleTrustManagerFactory {

    private static final Logger logger = LoggerFactory.getLogger(InsecureTrustManagerFactory.class);

    public static final TrustManagerFactory INSTANCE = new InsecureTrustManagerFactory();

    private static final TrustManager tm = new X509ExtendedTrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String s) {
            log("client", chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String s, Socket socket) {
            log("client", chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String s, SSLEngine sslEngine) {
            log("client", chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String s) {
            log("server", chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String s, Socket socket) {
            log("server", chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String s, SSLEngine sslEngine) {
            log("server", chain);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return EmptyArrays.EMPTY_X509_CERTIFICATES;
        }

        private void log(String type, X509Certificate[] chain) {
            logger.debug("Accepting a {} certificate: {}", type, chain[0].getSubjectDN());
        }
    };

    private InsecureTrustManagerFactory() {
    }

    @Override
    protected void engineInit(KeyStore keyStore) throws Exception {
    }

    @Override
    protected void engineInit(ManagerFactoryParameters managerFactoryParameters) throws Exception {
    }

    @Override
    protected TrustManager[] engineGetTrustManagers() {
        return new TrustManager[] { tm };
    }
}