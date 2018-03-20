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
import org.asynchttpclient.proxy.ProxyType;
import org.asynchttpclient.uri.Uri;

public interface ChannelPoolPartitioning {

  Object getPartitionKey(Uri uri, String virtualHost, ProxyServer proxyServer);

  enum PerHostChannelPoolPartitioning implements ChannelPoolPartitioning {

    INSTANCE;

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
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      CompositePartitionKey that = (CompositePartitionKey) o;

      if (proxyPort != that.proxyPort) return false;
      if (targetHostBaseUrl != null ? !targetHostBaseUrl.equals(that.targetHostBaseUrl) : that.targetHostBaseUrl != null)
        return false;
      if (virtualHost != null ? !virtualHost.equals(that.virtualHost) : that.virtualHost != null) return false;
      if (proxyHost != null ? !proxyHost.equals(that.proxyHost) : that.proxyHost != null) return false;
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
