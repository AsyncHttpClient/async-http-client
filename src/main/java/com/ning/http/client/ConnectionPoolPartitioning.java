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
package com.ning.http.client;

import com.ning.http.client.uri.Uri;
import com.ning.http.util.AsyncHttpProviderUtils;

public interface ConnectionPoolPartitioning {

    public class ProxyPartitionKey {
        private final String proxyUrl;
        private final String targetHostBaseUrl;

        public ProxyPartitionKey(String proxyUrl, String targetHostBaseUrl) {
            this.proxyUrl = proxyUrl;
            this.targetHostBaseUrl = targetHostBaseUrl;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((proxyUrl == null) ? 0 : proxyUrl.hashCode());
            result = prime * result + ((targetHostBaseUrl == null) ? 0 : targetHostBaseUrl.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (!(obj instanceof ProxyPartitionKey))
                return false;
            ProxyPartitionKey other = (ProxyPartitionKey) obj;
            if (proxyUrl == null) {
                if (other.proxyUrl != null)
                    return false;
            } else if (!proxyUrl.equals(other.proxyUrl))
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
                    .append("ProxyPartitionKey(proxyUrl=").append(proxyUrl)//
                    .append(", targetHostBaseUrl=").append(targetHostBaseUrl)//
                    .toString();
        }
    }

    Object getPartitionKey(Uri uri, ProxyServer proxyServer);

    public enum PerHostConnectionPoolPartitioning implements ConnectionPoolPartitioning {

        INSTANCE;

        public Object getPartitionKey(Uri uri, ProxyServer proxyServer) {
            String targetHostBaseUrl = AsyncHttpProviderUtils.getBaseUrl(uri);
            return proxyServer != null ? new ProxyPartitionKey(proxyServer.getUrl(), targetHostBaseUrl) : targetHostBaseUrl;
        }
    }
}
