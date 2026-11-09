/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.channel;

import java.util.Objects;

import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.asynchttpclient.uri.Uri;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface ChannelPoolPartitioning {

    Object getPartitionKey(Uri uri, String virtualHost, ProxyServer proxyServer);

    enum PerHostChannelPoolPartitioning implements ChannelPoolPartitioning {

        INSTANCE;

        @Override
        public Object getPartitionKey(Uri uri, @Nullable String virtualHost, @Nullable ProxyServer proxyServer) {
            if (proxyServer == null && virtualHost == null) {
                return new PartitionKey(uri.getScheme(), uri.getHost(), uri.getExplicitPort());
            }

            if (proxyServer != null) {
                return new CompositePartitionKey(
                        uri.getScheme(),
                        uri.getHost(),
                        uri.getExplicitPort(),
                        virtualHost,
                        proxyServer.getHost(),
                        uri.isSecured() && proxyServer.getProxyType().isHttp() ?
                                proxyServer.getSecuredPort() :
                                proxyServer.getPort(),
                        proxyServer.getProxyType());
            }

            return new CompositePartitionKey(
                    uri.getScheme(),
                    uri.getHost(),
                    uri.getExplicitPort(),
                    virtualHost,
                    null,
                    0,
                    null);
        }
    }

    class PartitionKey {
        private final String scheme;
        private final String host;
        private final int port;

        PartitionKey(String scheme, String host, int port) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            PartitionKey that = (PartitionKey) o;
            return port == that.port && Objects.equals(scheme, that.scheme) && Objects.equals(host, that.host);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(scheme);
            result = 31 * result + Objects.hashCode(host);
            result = 31 * result + port;
            return result;
        }
    }

    class CompositePartitionKey {
        // The target's scheme, host and port rather than Uri.getBaseUrl(), which would build a string on
        // every lookup only to compare and hash it.
        private final String scheme;
        private final String host;
        private final int port;
        private final @Nullable String virtualHost;
        private final @Nullable String proxyHost;
        private final int proxyPort;
        private final @Nullable ProxyType proxyType;

        CompositePartitionKey(String scheme, String host, int port, @Nullable String virtualHost, @Nullable String proxyHost, int proxyPort,
                              @Nullable ProxyType proxyType) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.virtualHost = virtualHost;
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.proxyType = proxyType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            CompositePartitionKey that = (CompositePartitionKey) o;

            if (port != that.port || proxyPort != that.proxyPort) {
                return false;
            }
            if (!Objects.equals(host, that.host) || !Objects.equals(scheme, that.scheme)) {
                return false;
            }
            if (!Objects.equals(virtualHost, that.virtualHost)) {
                return false;
            }
            if (!Objects.equals(proxyHost, that.proxyHost)) {
                return false;
            }
            return proxyType == that.proxyType;
        }

        @Override
        public int hashCode() {
            // Spelled out: Objects.hash would allocate a varargs array and box proxyPort on every lookup.
            int result = Objects.hashCode(scheme);
            result = 31 * result + Objects.hashCode(host);
            result = 31 * result + port;
            result = 31 * result + Objects.hashCode(virtualHost);
            result = 31 * result + Objects.hashCode(proxyHost);
            result = 31 * result + proxyPort;
            result = 31 * result + Objects.hashCode(proxyType);
            return result;
        }

        @Override
        public String toString() {
            return "CompositePartitionKey(" +
                    "targetHostBaseUrl=" + scheme + "://" + host + ':' + port +
                    ", virtualHost=" + virtualHost +
                    ", proxyHost=" + proxyHost +
                    ", proxyPort=" + proxyPort +
                    ", proxyType=" + proxyType +
                    ")";
        }
    }
}
