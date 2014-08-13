/*
 * Copyright (c) Will Sargent. All rights reserved.
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
package org.asynchttpclient.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Principal;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * A HostnameChecker proxy.
 */
public class ProxyHostnameChecker implements HostnameChecker {

    public final static byte TYPE_TLS = 1;

    private final Object checker = getHostnameChecker();

    public ProxyHostnameChecker() {
    }

    private Object getHostnameChecker() {
        final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            @SuppressWarnings("unchecked")
            final Class<Object> hostnameCheckerClass = (Class<Object>) classLoader.loadClass("sun.security.util.HostnameChecker");
            final Method instanceMethod = hostnameCheckerClass.getMethod("getInstance", Byte.TYPE);
            return instanceMethod.invoke(null, TYPE_TLS);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public void match(String hostname, X509Certificate peerCertificate) throws CertificateException {
        try {
            final Class<?> hostnameCheckerClass = checker.getClass();
            final Method checkMethod = hostnameCheckerClass.getMethod("match", String.class, X509Certificate.class);
            checkMethod.invoke(checker, hostname, peerCertificate);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof CertificateException) {
                throw (CertificateException) t;
            } else {
                throw new IllegalStateException(e);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean match(String hostname, Principal principal) {
        try {
            final Class<?> hostnameCheckerClass = checker.getClass();
            final Method checkMethod = hostnameCheckerClass.getMethod("match", String.class, Principal.class);
            return (Boolean) checkMethod.invoke(null, hostname, principal);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

}
