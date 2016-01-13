/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.channel;

import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.HttpUtils;

public interface ChannelPoolPartitioning {

    class ProxyPartitionKey {
        private final String proxyHost;
        private final int proxyPort;
        private final boolean secured;
        private final String targetHostBaseUrl;

        public ProxyPartitionKey(String proxyHost, int proxyPort, boolean secured, String targetHostBaseUrl) {
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.secured = secured;
            this.targetHostBaseUrl = targetHostBaseUrl;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((proxyHost == null) ? 0 : proxyHost.hashCode());
            result = prime * result + proxyPort;
            result = prime * result + (secured ? 1231 : 1237);
            result = prime * result + ((targetHostBaseUrl == null) ? 0 : targetHostBaseUrl.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ProxyPartitionKey other = (ProxyPartitionKey) obj;
            if (proxyHost == null) {
                if (other.proxyHost != null)
                    return false;
            } else if (!proxyHost.equals(other.proxyHost))
                return false;
            if (proxyPort != other.proxyPort)
                return false;
            if (secured != other.secured)
                return false;
            if (targetHostBaseUrl == null) {
                if (other.targetHostBaseUrl != null)
                    return false;
            } else if (!targetHostBaseUrl.equals(other.targetHostBaseUrl))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return new StringBuilder()//
                    .append("ProxyPartitionKey(proxyHost=").append(proxyHost)//
                    .append(", proxyPort=").append(proxyPort)//
                    .append(", secured=").append(secured)//
                    .append(", targetHostBaseUrl=").append(targetHostBaseUrl)//
                    .toString();
        }
    }

    Object getPartitionKey(Uri uri, String virtualHost, ProxyServer proxyServer);

    enum PerHostChannelPoolPartitioning implements ChannelPoolPartitioning {

        INSTANCE;

        public Object getPartitionKey(Uri uri, String virtualHost, ProxyServer proxyServer) {
            String targetHostBaseUrl = virtualHost != null ? virtualHost : HttpUtils.getBaseUrl(uri);
            if (proxyServer != null) {
                return uri.isSecured() ? //
                new ProxyPartitionKey(proxyServer.getHost(), proxyServer.getSecuredPort(), true, targetHostBaseUrl)
                        : new ProxyPartitionKey(proxyServer.getHost(), proxyServer.getPort(), false, targetHostBaseUrl);
            } else {
                return targetHostBaseUrl;
            }
        }
    }
}
