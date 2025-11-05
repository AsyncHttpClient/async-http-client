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

/**
 * Strategy interface for determining how channels are partitioned in the channel pool.
 * <p>
 * Channel pool partitioning allows different strategies for grouping and reusing
 * persistent connections based on various criteria such as target host, virtual host,
 * and proxy configuration.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Using the default per-host partitioning
 * ChannelPoolPartitioning partitioning = PerHostChannelPoolPartitioning.INSTANCE;
 *
 * // Get partition key for a request
 * Uri uri = new Uri("https", null, "example.com", 443, "/api", null);
 * Object key = partitioning.getPartitionKey(uri, null, null);
 * }</pre>
 */
public interface ChannelPoolPartitioning {

  /**
   * Computes a partition key for the given request parameters.
   * <p>
   * The partition key is used to group channels in the pool, allowing reuse of
   * connections to the same destination. The key should uniquely identify the
   * combination of target host, virtual host, and proxy configuration.
   * </p>
   *
   * @param uri the target URI of the request
   * @param virtualHost the virtual host header value, or {@code null} if not specified
   * @param proxyServer the proxy server configuration, or {@code null} if no proxy is used
   * @return a partition key object that uniquely identifies this connection configuration
   */
  Object getPartitionKey(Uri uri, String virtualHost, ProxyServer proxyServer);

  /**
   * Default channel pool partitioning strategy that partitions channels by host.
   * <p>
   * This implementation creates partition keys based on:
   * </p>
   * <ul>
   *   <li>Target host base URL (scheme, host, and port)</li>
   *   <li>Virtual host header (if specified)</li>
   *   <li>Proxy server configuration (if used)</li>
   * </ul>
   * <p>
   * For simple requests without virtual hosts or proxies, the partition key is
   * just the target host base URL. For more complex scenarios, a composite key
   * is created that includes all relevant parameters.
   * </p>
   */
  enum PerHostChannelPoolPartitioning implements ChannelPoolPartitioning {

    /**
     * Singleton instance of the per-host channel pool partitioning strategy.
     */
    INSTANCE;

    /**
     * Computes a partition key based on the target host, virtual host, and proxy configuration.
     * <p>
     * Returns a simple string key (the base URL) for basic requests, or a
     * {@link CompositePartitionKey} for requests with virtual hosts or proxies.
     * </p>
     *
     * @param uri the target URI of the request
     * @param virtualHost the virtual host header value, or {@code null} if not specified
     * @param proxyServer the proxy server configuration, or {@code null} if no proxy is used
     * @return a partition key (String or CompositePartitionKey) uniquely identifying this connection
     */
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

  /**
   * Composite partition key for complex connection scenarios.
   * <p>
   * This class represents a partition key that includes multiple components:
   * target host, virtual host, and proxy configuration. It is used when
   * requests require more than just the target host URL to uniquely identify
   * a connection pool partition.
   * </p>
   * <p>
   * Instances are immutable and implement proper {@code equals()} and
   * {@code hashCode()} methods for use as map keys.
   * </p>
   */
  class CompositePartitionKey {
    private final String targetHostBaseUrl;
    private final String virtualHost;
    private final String proxyHost;
    private final int proxyPort;
    private final ProxyType proxyType;

    /**
     * Creates a new composite partition key.
     *
     * @param targetHostBaseUrl the base URL of the target host (scheme, host, and port)
     * @param virtualHost the virtual host header value, or {@code null}
     * @param proxyHost the proxy server hostname, or {@code null}
     * @param proxyPort the proxy server port
     * @param proxyType the type of proxy (HTTP, SOCKS, etc.), or {@code null}
     */
    CompositePartitionKey(String targetHostBaseUrl, String virtualHost, String proxyHost, int proxyPort, ProxyType proxyType) {
      this.targetHostBaseUrl = targetHostBaseUrl;
      this.virtualHost = virtualHost;
      this.proxyHost = proxyHost;
      this.proxyPort = proxyPort;
      this.proxyType = proxyType;
    }

    /**
     * Compares this composite key with another object for equality.
     * <p>
     * Two composite keys are equal if all their components (target host, virtual host,
     * proxy host, proxy port, and proxy type) are equal.
     * </p>
     *
     * @param o the object to compare with
     * @return {@code true} if the objects are equal, {@code false} otherwise
     */
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

    /**
     * Returns a hash code value for this composite key.
     * <p>
     * The hash code is computed based on all components of the key to ensure
     * proper behavior when used in hash-based collections.
     * </p>
     *
     * @return a hash code value for this object
     */
    @Override
    public int hashCode() {
      int result = targetHostBaseUrl != null ? targetHostBaseUrl.hashCode() : 0;
      result = 31 * result + (virtualHost != null ? virtualHost.hashCode() : 0);
      result = 31 * result + (proxyHost != null ? proxyHost.hashCode() : 0);
      result = 31 * result + proxyPort;
      result = 31 * result + (proxyType != null ? proxyType.hashCode() : 0);
      return result;
    }

    /**
     * Returns a string representation of this composite key.
     * <p>
     * The string includes all components of the key for debugging and logging purposes.
     * </p>
     *
     * @return a string representation of this object
     */
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
