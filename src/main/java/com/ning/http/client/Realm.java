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

public class Realm {

    private final String principal;
    private final String password;
    private final AuthScheme scheme;
    private final String realmName;
    private final String nonce;
    private final String algorithm;
    private final String response;
    private final String qop;
    private final String nc;
    private final String cnonce;
    private final String uri;
    private final String methodName;

    public enum AuthScheme {
        DIGEST,
        BASIC
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
                  String uri, String method) {
        
        this.principal = principal;
        this.password = password;
        this.scheme = scheme;
        this.realmName = realmName;
        this.nonce = nonce;
        this.algorithm = algorithm;
        this.response = response;
        this.qop = qop;
        this.nc = nc;
        this.cnonce = cnonce;
        this.uri = uri;
        this.methodName = method;
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

    public String getMethodName() {
        return methodName;
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

    public class Builder {

        private String principal = "";
        private String password = "";
        private AuthScheme scheme = AuthScheme.BASIC;
        private String realmName = "";
        private String nonce = "";
        private String algorithm = "";
        private String response = "";
        private String qop  = "";
        private String nc = "";
        private String cnonce = "";
        private String uri = "";
        private String methodName = "GET";

        public String getPrincipal() {
            return principal;
        }

        public Builder setPrincipal(String principal) {
            this.principal = principal;
            return this;
        }

        public String getPassword() {
            return password;
        }

        public Builder setPassword(String password) {
            this.password = password;
            return this;
        }

        public AuthScheme getScheme() {
            return scheme;
        }

        public Builder setScheme(AuthScheme scheme) {
            this.scheme = scheme;
            return this;
        }

        public String getRealmName() {
            return realmName;
        }

        public Builder setRealmName(String realmName) {
            this.realmName = realmName;
            return this;
        }

        public String getNonce() {
            return nonce;
        }

        public Builder setNonce(String nonce) {
            this.nonce = nonce;
            return this;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public Builder setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public String getResponse() {
            return response;
        }

        public Builder setResponse(String response) {
            this.response = response;
            return this;
        }

        public String getQop() {
            return qop;
        }

        public Builder setQop(String qop) {
            this.qop = qop;
            return this;
        }

        public String getNc() {
            return nc;
        }

        public Builder setNc(String nc) {
            this.nc = nc;
            return this;
        }

        public String getCnonce() {
            return cnonce;
        }

        public Builder setCnonce(String cnonce) {
            this.cnonce = cnonce;
            return this;
        }

        public String getUri() {
            return uri;
        }

        public Builder setUri(String uri) {
            this.uri = uri;
            return this;
        }

        public String getMethodName() {
            return methodName;
        }

        public void setMethodName(String methodName) {
            this.methodName = methodName;
        }

        public Realm build(){
            return new Realm(scheme,principal,password,realmName, nonce,algorithm,response,qop,nc,cnonce,uri, methodName);
        }
    }

}
