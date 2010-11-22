package org.sonatype.ahc.suite.util;

/*
 * Copyright (c) 2010 Sonatype, Inc. All rights reserved.
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

import org.sonatype.tests.http.server.jetty.impl.JettyServerProvider.CertificateHolder;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAPrivateKey;
import java.util.HashSet;
import java.util.Set;

public class CertUtil {

    public static final class AliasKeyManager
            extends X509ExtendedKeyManager {

        X509ExtendedKeyManager realManager = null;

        String alias = null;

        public AliasKeyManager(X509ExtendedKeyManager keyManager, String alias) {
            realManager = keyManager;
            this.alias = alias;
        }

        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return alias;
        }

        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return realManager.chooseServerAlias(keyType, issuers, socket);
        }

        public java.security.cert.X509Certificate[] getCertificateChain(String alias) {
            return realManager.getCertificateChain(alias);
        }

        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return realManager.getClientAliases(keyType, issuers);
        }

        public PrivateKey getPrivateKey(String alias) {
            return realManager.getPrivateKey(alias);
        }

        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return realManager.getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return alias;
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return realManager.chooseEngineServerAlias(keyType, issuers, engine);
        }
    }

    public static final class CustomTrustManager
            implements X509TrustManager {
        Set<X509Certificate> trustedIssuers = new HashSet<X509Certificate>();

        public void checkClientTrusted(X509Certificate[] arg0, String arg1)
                throws CertificateException {
        }

        public void checkServerTrusted(X509Certificate[] arg0, String arg1)
                throws CertificateException {
            trustedIssuers.add(arg0[0]);
        }

        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return trustedIssuers.toArray(new X509Certificate[trustedIssuers.size()]);
        }

    }

    public static CertificateHolder getCertificate(String alias, String keystorePath, String keystorePass) {
        InputStream is = null;
        Certificate cert = null;
        DSAPrivateKey key;
        try {
            try {
                is = new FileInputStream(new File(keystorePath));
            }
            catch (IOException e) {
                is = CertUtil.class.getClassLoader().getResource(keystorePath).openStream();
            }
            KeyStore keystore = KeyStore.getInstance("JKS");
            keystore.load(is, keystorePass == null ? null : keystorePass.toString().toCharArray());
            cert = keystore.getCertificate(alias);
            key = (DSAPrivateKey) keystore.getKey(alias, keystorePass.toCharArray());
        }
        catch (Throwable t) {
            throw new RuntimeException(t.getMessage(), t);
        }
        finally {
            if (is != null) {
                try {
                    is.close();
                }
                catch (IOException e) {
                }
            }
        }
        return new CertificateHolder(key, cert);
    }

    public static SSLContext sslContext(String path, String password, String forcedAlias) {
        try {
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
            KeyStore keyStore = KeyStore.getInstance("JKS");

            InputStream keyInput = null;
            try {
                try {
                    keyInput = new FileInputStream(new File(path));
                }
                catch (IOException e) {
                    keyInput = CertUtil.class.getClassLoader().getResourceAsStream(path);
                }

                keyStore.load(keyInput, password.toCharArray());
            }
            finally {
                if (keyInput != null) {
                    keyInput.close();
                }
            }

            keyManagerFactory.init(keyStore, password.toCharArray());

            KeyManager[] kms = keyManagerFactory.getKeyManagers();
            for (int i = 0; i < kms.length; i++) {
                if (kms[i] instanceof X509KeyManager) {
                    kms[i] = new AliasKeyManager((X509ExtendedKeyManager) kms[i], forcedAlias);
                }
            }

            TrustManager[] _trustManagers = new TrustManager[]{new CustomTrustManager()};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kms, _trustManagers, null);

            return context;
        }
        catch (Throwable t) {
            throw new RuntimeException(t.getMessage(), t);
        }
    }

}
