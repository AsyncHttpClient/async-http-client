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
package org.asynchttpclient.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.asynchttpclient.Realm;

/**
 * Represents a proxy server.
 */
public class ProxyServer {

    public static ProxyServerBuilder newProxyServer(String host, int port) {
        return new ProxyServerBuilder(host, port);
    }

    private final String host;
    private final int port;
    private final int securedPort;
    private final Realm realm;
    private final List<String> nonProxyHosts;
    private final boolean forceHttp10;

    public ProxyServer(String host, int port, int securedPort, Realm realm, List<String> nonProxyHosts, boolean forceHttp10) {
        this.host = host;
        this.port = port;
        this.securedPort = securedPort;
        this.realm = realm;
        this.nonProxyHosts = nonProxyHosts;
        this.forceHttp10 = forceHttp10;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getSecuredPort() {
        return securedPort;
    }

    public List<String> getNonProxyHosts() {
        return nonProxyHosts;
    }

    public boolean isForceHttp10() {
        return forceHttp10;
    }

    public Realm getRealm() {
        return realm;
    }

    public static class ProxyServerBuilder {

        private String host;
        private int port;
        private int securedPort;
        private Realm realm;
        private List<String> nonProxyHosts;
        private boolean forceHttp10;

        public ProxyServerBuilder(String host, int port) {
            this.host = host;
            this.port = port;
            this.securedPort = port;
        }

        public ProxyServerBuilder securedPort(int securedPort) {
            this.securedPort = securedPort;
            return this;
        }

        public ProxyServerBuilder realm(Realm realm) {
            this.realm = realm;
            return this;
        }

        public ProxyServerBuilder nonProxyHost(String nonProxyHost) {
            if (nonProxyHosts == null)
                nonProxyHosts = new ArrayList<String>(1);
            nonProxyHosts.add(nonProxyHost);
            return this;
        }
        
        public ProxyServerBuilder nonProxyHosts(List<String> nonProxyHosts) {
            this.nonProxyHosts = nonProxyHosts;
            return this;
        }

        public ProxyServerBuilder forceHttp10(boolean forceHttp10) {
            this.forceHttp10 = forceHttp10;
            return this;
        }

        public ProxyServer build() {
            List<String> nonProxyHosts = this.nonProxyHosts != null ? Collections.unmodifiableList(this.nonProxyHosts) : Collections.emptyList();
            // FIXME!!!!!!!!!!!!!!!!!!!!!!!!
            Realm realm = this.realm != null && !this.realm.isTargetProxy() ? Realm.newRealm(this.realm).targetProxy(true).build() : this.realm;

            return new ProxyServer(host, port, securedPort, realm, nonProxyHosts, forceHttp10);
        }
    }
}
