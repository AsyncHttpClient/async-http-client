/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
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

import org.asynchttpclient.Realm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.asynchttpclient.util.Assertions.assertNotNull;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

/**
 * Represents a proxy server configuration.
 * <p>
 * This class encapsulates all the information needed to connect through a proxy server,
 * including the host, port, authentication realm, and patterns for hosts that should
 * bypass the proxy. Both HTTP and SOCKS proxy types are supported.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Simple HTTP proxy
 * ProxyServer proxy = new ProxyServer.Builder("proxy.example.com", 8080)
 *     .build();
 *
 * // Proxy with authentication
 * Realm realm = new Realm.Builder("username", "password")
 *     .setScheme(Realm.AuthScheme.BASIC)
 *     .build();
 * ProxyServer proxy = new ProxyServer.Builder("proxy.example.com", 8080)
 *     .setRealm(realm)
 *     .build();
 *
 * // Proxy with non-proxy hosts
 * ProxyServer proxy = new ProxyServer.Builder("proxy.example.com", 8080)
 *     .setNonProxyHost("*.internal.com")
 *     .setNonProxyHost("localhost")
 *     .build();
 * }</pre>
 */
public class ProxyServer {

  private final String host;
  private final int port;
  private final int securedPort;
  private final Realm realm;
  private final List<String> nonProxyHosts;
  private final ProxyType proxyType;

  /**
   * Creates a new proxy server with the specified configuration.
   *
   * @param host the proxy server hostname
   * @param port the proxy server port for non-SSL connections
   * @param securedPort the proxy server port for SSL connections
   * @param realm the authentication realm (can be null)
   * @param nonProxyHosts list of host patterns that should bypass the proxy (can be null)
   * @param proxyType the type of proxy (HTTP or SOCKS)
   */
  public ProxyServer(String host, int port, int securedPort, Realm realm, List<String> nonProxyHosts,
                     ProxyType proxyType) {
    this.host = host;
    this.port = port;
    this.securedPort = securedPort;
    this.realm = realm;
    this.nonProxyHosts = nonProxyHosts;
    this.proxyType = proxyType;
  }

  /**
   * Returns the proxy server hostname.
   *
   * @return the proxy server hostname
   */
  public String getHost() {
    return host;
  }

  /**
   * Returns the proxy server port for non-SSL connections.
   *
   * @return the proxy server port
   */
  public int getPort() {
    return port;
  }

  /**
   * Returns the proxy server port for SSL connections.
   *
   * @return the secured proxy server port
   */
  public int getSecuredPort() {
    return securedPort;
  }

  /**
   * Returns the list of host patterns that should bypass the proxy.
   *
   * @return the non-proxy hosts list (never null, may be empty)
   */
  public List<String> getNonProxyHosts() {
    return nonProxyHosts;
  }

  /**
   * Returns the authentication realm for the proxy server.
   *
   * @return the authentication realm, or null if no authentication is configured
   */
  public Realm getRealm() {
    return realm;
  }

  /**
   * Returns the type of proxy (HTTP or SOCKS).
   *
   * @return the proxy type
   */
  public ProxyType getProxyType() {
    return proxyType;
  }

  /**
   * Checks whether proxy should be used according to nonProxyHosts settings of
   * it, or we want to go directly to target host. If <code>null</code> proxy is
   * passed in, this method returns true -- since there is NO proxy, we should
   * avoid to use it. Simple hostname pattern matching using "*" are supported,
   * but only as prefixes.
   *
   * @param hostname the hostname
   * @return true if we have to ignore proxy use (obeying non-proxy hosts
   * settings), false otherwise.
   * @see <a href=
   * "https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html">Networking
   * Properties</a>
   */
  public boolean isIgnoredForHost(String hostname) {
    assertNotNull(hostname, "hostname");
    if (isNonEmpty(nonProxyHosts)) {
      for (String nonProxyHost : nonProxyHosts) {
        if (matchNonProxyHost(hostname, nonProxyHost))
          return true;
      }
    }

    return false;
  }

  private boolean matchNonProxyHost(String targetHost, String nonProxyHost) {

    if (nonProxyHost.length() > 1) {
      if (nonProxyHost.charAt(0) == '*') {
        return targetHost.regionMatches(true, targetHost.length() - nonProxyHost.length() + 1, nonProxyHost, 1,
                nonProxyHost.length() - 1);
      } else if (nonProxyHost.charAt(nonProxyHost.length() - 1) == '*')
        return targetHost.regionMatches(true, 0, nonProxyHost, 0, nonProxyHost.length() - 1);
    }

    return nonProxyHost.equalsIgnoreCase(targetHost);
  }

  /**
   * Builder for creating ProxyServer instances.
   */
  public static class Builder {

    private String host;
    private int port;
    private int securedPort;
    private Realm realm;
    private List<String> nonProxyHosts;
    private ProxyType proxyType;

    /**
     * Creates a new proxy server builder with the specified host and port.
     * <p>
     * The secured port is initially set to the same value as the port.
     * </p>
     *
     * @param host the proxy server hostname
     * @param port the proxy server port
     */
    public Builder(String host, int port) {
      this.host = host;
      this.port = port;
      this.securedPort = port;
    }

    /**
     * Sets the proxy server port for SSL connections.
     *
     * @param securedPort the secured port number
     * @return this builder for method chaining
     */
    public Builder setSecuredPort(int securedPort) {
      this.securedPort = securedPort;
      return this;
    }

    /**
     * Sets the authentication realm for the proxy server.
     *
     * @param realm the authentication realm
     * @return this builder for method chaining
     */
    public Builder setRealm(Realm realm) {
      this.realm = realm;
      return this;
    }

    /**
     * Sets the authentication realm using a realm builder.
     *
     * @param realm the realm builder
     * @return this builder for method chaining
     */
    public Builder setRealm(Realm.Builder realm) {
      this.realm = realm.build();
      return this;
    }

    /**
     * Adds a single host pattern that should bypass the proxy.
     * <p>
     * Patterns can use wildcards (*) as prefixes or suffixes. For example:
     * </p>
     * <ul>
     *   <li>*.example.com - matches any subdomain of example.com</li>
     *   <li>192.168.* - matches any IP starting with 192.168.</li>
     *   <li>localhost - exact match</li>
     * </ul>
     *
     * @param nonProxyHost the host pattern to bypass the proxy
     * @return this builder for method chaining
     */
    public Builder setNonProxyHost(String nonProxyHost) {
      if (nonProxyHosts == null)
        nonProxyHosts = new ArrayList<>(1);
      nonProxyHosts.add(nonProxyHost);
      return this;
    }

    /**
     * Sets the list of host patterns that should bypass the proxy.
     *
     * @param nonProxyHosts the list of host patterns
     * @return this builder for method chaining
     */
    public Builder setNonProxyHosts(List<String> nonProxyHosts) {
      this.nonProxyHosts = nonProxyHosts;
      return this;
    }

    /**
     * Sets the proxy type (HTTP or SOCKS).
     *
     * @param proxyType the proxy type
     * @return this builder for method chaining
     */
    public Builder setProxyType(ProxyType proxyType) {
      this.proxyType = proxyType;
      return this;
    }

    /**
     * Builds a new ProxyServer instance with the configured settings.
     * <p>
     * If no proxy type is set, defaults to HTTP. If no non-proxy hosts are set,
     * uses an empty list.
     * </p>
     *
     * @return a new ProxyServer instance
     */
    public ProxyServer build() {
      List<String> nonProxyHosts = this.nonProxyHosts != null ? Collections.unmodifiableList(this.nonProxyHosts)
              : Collections.emptyList();
      ProxyType proxyType = this.proxyType != null ? this.proxyType : ProxyType.HTTP;
      return new ProxyServer(host, port, securedPort, realm, nonProxyHosts, proxyType);
    }
  }
}
