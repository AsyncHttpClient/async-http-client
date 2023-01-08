/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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

import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.asynchttpclient.uri.Uri;

import java.util.Objects;

@FunctionalInterface
public interface ChannelPoolPartitioning {

    Object getPartitionKey(Uri uri, String virtualHost, ProxyServer proxyServer);

    enum PerHostChannelPoolPartitioning implements ChannelPoolPartitioning {

        INSTANCE;

        @Override
        public Object getPartitionKey(Uri uri, String virtualHost, ProxyServer proxyServer) {
            String targetHostBaseUrl = uri.getBaseUrl();
            if (proxyServer == null) {
                if (virtualHost == null) {
                    return targetHostBaseUrl;
                } else {
                    return new CompositePartitionKey(
                            targetHostBaseUrl,
                            virtualHost,
                            null,
                            0,
                            null);
                }
            } else {
                return new CompositePartitionKey(
                        targetHostBaseUrl,
                        virtualHost,
                        proxyServer.getHost(),
                        uri.isSecured() && proxyServer.getProxyType() == ProxyType.HTTP ?
                                proxyServer.getSecuredPort() :
                                proxyServer.getPort(),
                        proxyServer.getProxyType());
            }
        }
    }

    class CompositePartitionKey {
        private final String targetHostBaseUrl;
        private final String virtualHost;
        private final String proxyHost;
        private final int proxyPort;
        private final ProxyType proxyType;

        CompositePartitionKey(String targetHostBaseUrl, String virtualHost, String proxyHost, int proxyPort, ProxyType proxyType) {
            this.targetHostBaseUrl = targetHostBaseUrl;
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

            if (proxyPort != that.proxyPort) {
                return false;
            }
            if (!Objects.equals(targetHostBaseUrl, that.targetHostBaseUrl)) {
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
            int result = targetHostBaseUrl != null ? targetHostBaseUrl.hashCode() : 0;
            result = 31 * result + (virtualHost != null ? virtualHost.hashCode() : 0);
            result = 31 * result + (proxyHost != null ? proxyHost.hashCode() : 0);
            result = 31 * result + proxyPort;
            result = 31 * result + (proxyType != null ? proxyType.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "CompositePartitionKey(" +
                    "targetHostBaseUrl=" + targetHostBaseUrl +
                    ", virtualHost=" + virtualHost +
                    ", proxyHost=" + proxyHost +
                    ", proxyPort=" + proxyPort +
                    ", proxyType=" + proxyType;
        }
    }
}
