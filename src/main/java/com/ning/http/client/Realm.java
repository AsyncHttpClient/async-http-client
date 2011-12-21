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
 *
 */
package com.ning.http.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * This class is required when authentication is needed. The class support DIGEST and BASIC.
 */
public class Realm {

    private static final String NC = "00000001";

    private final String principal;
    private final String password;
    private final AuthScheme scheme;
    private final String realmName;
    private final String nonce;
    private final String algorithm;
    private final String response;
    private final String opaque;
    private final String qop;
    private final String nc;
    private final String cnonce;
    private final String uri;
    private final String methodName;
    private final boolean usePreemptiveAuth;
    private final String enc;
    private final String host;
    private final boolean messageType2Received;

    private final String domain;

    public enum AuthScheme {
        DIGEST,
        BASIC,
        NTLM,
        SPNEGO,
        KERBEROS,
        NONE
    }

    private Realm(AuthScheme scheme,
                  String principal,
                  String password,
                  String realmName,
                  String nonce,
                  String algorithm,
                  String response,
                  String qop,
                  String nc,
                  String cnonce,
                  String uri,
                  String method,
                  boolean usePreemptiveAuth,
                  String domain, String enc, String host, boolean messageType2Received,
                  String opaque) {

        this.principal = principal;
        this.password = password;
        this.scheme = scheme;
        this.realmName = realmName;
        this.nonce = nonce;
        this.algorithm = algorithm;
        this.response = response;
        this.opaque = opaque;
        this.qop = qop;
        this.nc = nc;
        this.cnonce = cnonce;
        this.uri = uri;
        this.methodName = method;
        this.usePreemptiveAuth = usePreemptiveAuth;
        this.domain = domain;
        this.enc = enc;
        this.host = host;
        this.messageType2Received = messageType2Received;
    }

    public String getPrincipal() {
        return principal;
    }

    public String getPassword() {
        return password;
    }

    public AuthScheme getAuthScheme() {
        return scheme;
    }

    public AuthScheme getScheme() {

        return scheme;
    }

    public String getRealmName() {
        return realmName;
    }

    public String getNonce() {
        return nonce;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getResponse() {
        return response;
    }

    public String getOpaque() {
        return opaque;
    }

    public String getQop() {
        return qop;
    }

    public String getNc() {
        return nc;
    }

    public String getCnonce() {
        return cnonce;
    }

    public String getUri() {
        return uri;
    }

    public String getEncoding() {
        return enc;
    }

    public String getMethodName() {
        return methodName;
    }

    /**
     * Return true is preemptive authentication is enabled
     *
     * @return true is preemptive authentication is enabled
     */
    public boolean getUsePreemptiveAuth() {
        return usePreemptiveAuth;
    }

    /**
     * Return the NTLM domain to use. This value should map the JDK
     *
     * @return the NTLM domain
     * @deprecated - use getNtlmDomain()
     */
    public String getDomain() {
        return domain;
    }

    /**
     * Return the NTLM domain to use. This value should map the JDK
     *
     * @return the NTLM domain
     */
    public String getNtlmDomain() {
        return domain;
    }

    /**
     * Return the NTLM host.
     *
     * @return the NTLM host
     */
    public String getNtlmHost() {
        return host;
    }

    public boolean isNtlmMessageType2Received() {
        return messageType2Received;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Realm realm = (Realm) o;

        if (algorithm != null ? !algorithm.equals(realm.algorithm) : realm.algorithm != null) return false;
        if (cnonce != null ? !cnonce.equals(realm.cnonce) : realm.cnonce != null) return false;
        if (nc != null ? !nc.equals(realm.nc) : realm.nc != null) return false;
        if (nonce != null ? !nonce.equals(realm.nonce) : realm.nonce != null) return false;
        if (password != null ? !password.equals(realm.password) : realm.password != null) return false;
        if (principal != null ? !principal.equals(realm.principal) : realm.principal != null) return false;
        if (qop != null ? !qop.equals(realm.qop) : realm.qop != null) return false;
        if (realmName != null ? !realmName.equals(realm.realmName) : realm.realmName != null) return false;
        if (response != null ? !response.equals(realm.response) : realm.response != null) return false;
        if (scheme != realm.scheme) return false;
        if (uri != null ? !uri.equals(realm.uri) : realm.uri != null) return false;

        return true;
    }

    @Override
    public String toString() {
        return "Realm{" +
                "principal='" + principal + '\'' +
                ", password='" + password + '\'' +
                ", scheme=" + scheme +
                ", realmName='" + realmName + '\'' +
                ", nonce='" + nonce + '\'' +
                ", algorithm='" + algorithm + '\'' +
                ", response='" + response + '\'' +
                ", qop='" + qop + '\'' +
                ", nc='" + nc + '\'' +
                ", cnonce='" + cnonce + '\'' +
                ", uri='" + uri + '\'' +
                ", methodName='" + methodName + '\'' +
                '}';
    }

    @Override
    public int hashCode() {
        int result = principal != null ? principal.hashCode() : 0;
        result = 31 * result + (password != null ? password.hashCode() : 0);
        result = 31 * result + (scheme != null ? scheme.hashCode() : 0);
        result = 31 * result + (realmName != null ? realmName.hashCode() : 0);
        result = 31 * result + (nonce != null ? nonce.hashCode() : 0);
        result = 31 * result + (algorithm != null ? algorithm.hashCode() : 0);
        result = 31 * result + (response != null ? response.hashCode() : 0);
        result = 31 * result + (qop != null ? qop.hashCode() : 0);
        result = 31 * result + (nc != null ? nc.hashCode() : 0);
        result = 31 * result + (cnonce != null ? cnonce.hashCode() : 0);
        result = 31 * result + (uri != null ? uri.hashCode() : 0);
        return result;
    }

    /**
     * A builder for {@link Realm}
     */
    public static class RealmBuilder {

        private static final Logger logger = LoggerFactory.getLogger(RealmBuilder.class);

        //
        //  Portions of code (newCnonce, newResponse) are highly inspired be Jetty 6 BasicAuthentication.java class.
        //  This code is already Apache licenced.
        //

        private String principal = "";
        private String password = "";
        private AuthScheme scheme = AuthScheme.NONE;
        private String realmName = "";
        private String nonce = "";
        private String algorithm = "MD5";
        private String response = "";
        private String opaque = "";
        private String qop = "auth";
        private String nc = "00000001";
        private String cnonce = "";
        private String uri = "";
        private String methodName = "GET";
        private boolean usePreemptive = false;
        private String domain = System.getProperty("http.auth.ntlm.domain", "");
        private String enc = "UTF-8";
        private String host = "localhost";
        private boolean messageType2Received = false;

        @Deprecated
        public String getDomain() {
            return domain;
        }

        @Deprecated
        public RealmBuilder setDomain(String domain) {
            this.domain = domain;
            return this;
        }

        public String getNtlmDomain() {
            return domain;
        }

        public RealmBuilder setNtlmDomain(String domain) {
            this.domain = domain;
            return this;
        }

        public String getNtlmHost() {
            return host;
        }

        public RealmBuilder setNtlmHost(String host) {
            this.host = host;
            return this;
        }


        public String getPrincipal() {
            return principal;
        }

        public RealmBuilder setPrincipal(String principal) {
            this.principal = principal;
            return this;
        }

        public String getPassword() {
            return password;
        }

        public RealmBuilder setPassword(String password) {
            this.password = password;
            return this;
        }

        public AuthScheme getScheme() {
            return scheme;
        }

        public RealmBuilder setScheme(AuthScheme scheme) {
            this.scheme = scheme;
            return this;
        }

        public String getRealmName() {
            return realmName;
        }

        public RealmBuilder setRealmName(String realmName) {
            this.realmName = realmName;
            return this;
        }

        public String getNonce() {
            return nonce;
        }

        public RealmBuilder setNonce(String nonce) {
            this.nonce = nonce;
            return this;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public RealmBuilder setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public String getResponse() {
            return response;
        }

        public RealmBuilder setResponse(String response) {
            this.response = response;
            return this;
        }

        public String getOpaque() {
            return this.opaque;
        }

        public RealmBuilder setOpaque(String opaque) {
            this.opaque = opaque;
            return this;
        }

        public String getQop() {
            return qop;
        }

        public RealmBuilder setQop(String qop) {
            this.qop = qop;
            return this;
        }

        public String getNc() {
            return nc;
        }

        public RealmBuilder setNc(String nc) {
            this.nc = nc;
            return this;
        }

        public String getUri() {
            return uri;
        }

        public RealmBuilder setUri(String uri) {
            this.uri = uri;
            return this;
        }

        public String getMethodName() {
            return methodName;
        }

        public RealmBuilder setMethodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public boolean getUsePreemptiveAuth() {
            return usePreemptive;
        }

        public RealmBuilder setUsePreemptiveAuth(boolean usePreemptiveAuth) {
            this.usePreemptive = usePreemptiveAuth;
            return this;
        }

        public RealmBuilder parseWWWAuthenticateHeader(String headerLine) {
            setRealmName(match(headerLine, "realm"));
            setNonce(match(headerLine, "nonce"));
            setAlgorithm(match(headerLine, "algorithm"));
            setOpaque(match(headerLine, "opaque"));
            setQop(match(headerLine, "qop"));
            if (getNonce() != null && !getNonce().equalsIgnoreCase("")) {
                setScheme(AuthScheme.DIGEST);
            } else {
                setScheme(AuthScheme.BASIC);
            }
            return this;
        }

        public RealmBuilder setNtlmMessageType2Received(boolean messageType2Received) {
            this.messageType2Received = messageType2Received;
            return this;
        }

        public RealmBuilder clone(Realm clone) {
            setRealmName(clone.getRealmName());
            setAlgorithm(clone.getAlgorithm());
            setMethodName(clone.getMethodName());
            setNc(clone.getNc());
            setNonce(clone.getNonce());
            setPassword(clone.getPassword());
            setPrincipal(clone.getPrincipal());
            setEnconding(clone.getEncoding());
            setOpaque(clone.getOpaque());
            setQop(clone.getQop());
            setScheme(clone.getScheme());
            setUri(clone.getUri());
            setUsePreemptiveAuth(clone.getUsePreemptiveAuth());
            setNtlmDomain(clone.getNtlmDomain());
            setNtlmHost(clone.getNtlmHost());
            setNtlmMessageType2Received(clone.isNtlmMessageType2Received());
            return this;
        }

        private void newCnonce() {
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] b = md.digest(String.valueOf(System.currentTimeMillis()).getBytes("ISO-8859-1"));
                cnonce = toHexString(b);
            } catch (Exception e) {
                throw new SecurityException(e);
            }
        }

        /**
         * TODO: A Pattern/Matcher may be better.
         */
        private String match(String headerLine, String token) {
            if (headerLine == null) {
                return "";
            }

            int match = headerLine.indexOf(token);
            if (match <= 0) return "";

            // = to skip
            match += token.length() + 1;
            int traillingComa = headerLine.indexOf(",", match);
            String value = headerLine.substring(match, traillingComa > 0 ? traillingComa : headerLine.length());
            value = value.endsWith("\"") ? value.substring(0, value.length() - 1) : value;
            return value.startsWith("\"") ? value.substring(1) : value;
        }

        public String getEncoding() {
            return enc;
        }

        public RealmBuilder setEnconding(String enc) {
            this.enc = enc;
            return this;
        }

        private void newResponse() throws UnsupportedEncodingException {
            MessageDigest md = null;
            try {
                md = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new SecurityException(e);
            }
            md.update(new StringBuilder(principal)
                    .append(":")
                    .append(realmName)
                    .append(":")
                    .append(password)
                    .toString().getBytes("ISO-8859-1"));
            byte[] ha1 = md.digest();

            md.reset();
            md.update(new StringBuilder(methodName)
                    .append(':')
                    .append(uri).toString().getBytes("ISO-8859-1"));
            byte[] ha2 = md.digest();

            md.update(new StringBuilder(toBase16(ha1))
                    .append(':')
                    .append(nonce)
                    .append(':')
                    .append(NC)
                    .append(':')
                    .append(cnonce)
                    .append(':')
                    .append(qop)
                    .append(':')
                    .append(toBase16(ha2)).toString().getBytes("ISO-8859-1"));
            byte[] digest = md.digest();

            response = toHexString(digest);
        }

        private static String toHexString(byte[] data) {
            StringBuilder buffer = new StringBuilder();
            for (int i = 0; i < data.length; i++) {
                buffer.append(Integer.toHexString((data[i] & 0xf0) >>> 4));
                buffer.append(Integer.toHexString(data[i] & 0x0f));
            }
            return buffer.toString();
        }

        private static String toBase16(byte[] bytes) {
            int base = 16;
            StringBuilder buf = new StringBuilder();
            for (byte b : bytes) {
                int bi = 0xff & b;
                int c = '0' + (bi / base) % base;
                if (c > '9')
                    c = 'a' + (c - '0' - 10);
                buf.append((char) c);
                c = '0' + bi % base;
                if (c > '9')
                    c = 'a' + (c - '0' - 10);
                buf.append((char) c);
            }
            return buf.toString();
        }

        /**
         * Build a {@link Realm}
         *
         * @return a {@link Realm}
         */
        public Realm build() {

            // Avoid generating
            if (nonce != null && !nonce.equals("")) {
                newCnonce();
                try {
                    newResponse();
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
            }

            return new Realm(scheme,
                    principal,
                    password,
                    realmName,
                    nonce,
                    algorithm,
                    response,
                    qop,
                    nc,
                    cnonce,
                    uri,
                    methodName,
                    usePreemptive,
                    domain,
                    enc,
                    host,
                    messageType2Received,
                    opaque);
        }
    }

}
