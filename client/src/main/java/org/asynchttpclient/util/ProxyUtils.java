/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.util;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyServerSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.proxyServer;

/**
 * Utilities for Proxy handling.
 *
 * @author cstamas
 */
public final class ProxyUtils {

  /**
   * The host to use as proxy.
   *
   * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html">Networking Properties</a>
   */
  public static final String PROXY_HOST = "http.proxyHost";
  /**
   * The port to use for the proxy.
   *
   * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html">Networking Properties</a>
   */
  public static final String PROXY_PORT = "http.proxyPort";
  /**
   * A specification of non-proxy hosts.
   *
   * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html">Networking Properties</a>
   */
  public static final String PROXY_NONPROXYHOSTS = "http.nonProxyHosts";
  private final static Logger logger = LoggerFactory.getLogger(ProxyUtils.class);
  private static final String PROPERTY_PREFIX = "org.asynchttpclient.AsyncHttpClientConfig.proxy.";

  /**
   * The username to use for authentication for the proxy server.
   */
  private static final String PROXY_USER = PROPERTY_PREFIX + "user";

  /**
   * The password to use for authentication for the proxy server.
   */
  private static final String PROXY_PASSWORD = PROPERTY_PREFIX + "password";

  private ProxyUtils() {
  }

  /**
   * Determines the proxy server to use for a given request.
   * <p>
   * This method first checks if a proxy server is explicitly configured on the request.
   * If not, it falls back to the proxy server selector configured on the client. The method
   * also verifies that the determined proxy is not configured to be ignored for the request's host.
   * </p>
   *
   * @param config  the global client configuration
   * @param request the HTTP request
   * @return the proxy server to be used for this request, or null if no proxy should be used
   */
  public static ProxyServer getProxyServer(AsyncHttpClientConfig config, Request request) {
    ProxyServer proxyServer = request.getProxyServer();
    if (proxyServer == null) {
      ProxyServerSelector selector = config.getProxyServerSelector();
      if (selector != null) {
        proxyServer = selector.select(request.getUri());
      }
    }
    return proxyServer != null && !proxyServer.isIgnoredForHost(request.getUri().getHost()) ? proxyServer : null;
  }

  /**
   * Creates a proxy server instance from the given properties.
   * Currently the default http.* proxy properties are supported as well as properties specific for AHC.
   *
   * @param properties the properties to evaluate. Must not be null.
   * @return a ProxyServer instance or null, if no valid properties were set.
   * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html">Networking Properties</a>
   * @see #PROXY_HOST
   * @see #PROXY_PORT
   * @see #PROXY_NONPROXYHOSTS
   */
  public static ProxyServerSelector createProxyServerSelector(Properties properties) {
    String host = properties.getProperty(PROXY_HOST);

    if (host != null) {
      int port = Integer.valueOf(properties.getProperty(PROXY_PORT, "80"));

      String principal = properties.getProperty(PROXY_USER);
      String password = properties.getProperty(PROXY_PASSWORD);

      Realm realm = null;
      if (principal != null) {
        realm = basicAuthRealm(principal, password).build();
      }

      ProxyServer.Builder proxyServer = proxyServer(host, port).setRealm(realm);

      String nonProxyHosts = properties.getProperty(PROXY_NONPROXYHOSTS);
      if (nonProxyHosts != null) {
        proxyServer.setNonProxyHosts(new ArrayList<>(Arrays.asList(nonProxyHosts.split("\\|"))));
      }

      ProxyServer proxy = proxyServer.build();
      return uri -> proxy;
    }

    return ProxyServerSelector.NO_PROXY_SELECTOR;
  }

  /**
   * Gets a proxy server selector based on the JDK default proxy selector.
   * <p>
   * This method creates a selector that uses Java's default {@link ProxySelector} to
   * determine proxy settings, which typically respects system proxy properties.
   * </p>
   *
   * @return the proxy server selector based on JDK defaults
   */
  public static ProxyServerSelector getJdkDefaultProxyServerSelector() {
    return createProxyServerSelector(ProxySelector.getDefault());
  }

  /**
   * Creates a proxy server selector based on the specified JDK proxy selector.
   * <p>
   * This method wraps a {@link ProxySelector} to provide proxy selection functionality
   * compatible with the async-http-client API. It handles HTTP proxies and properly
   * manages direct connections.
   * </p>
   *
   * @param proxySelector the JDK proxy selector to use (must not be null)
   * @return the proxy server selector
   */
  private static ProxyServerSelector createProxyServerSelector(final ProxySelector proxySelector) {
    return uri -> {
        try {
          URI javaUri = uri.toJavaNetURI();

          List<Proxy> proxies = proxySelector.select(javaUri);
          if (proxies != null) {
            // Loop through them until we find one that we know how to use
            for (Proxy proxy : proxies) {
              switch (proxy.type()) {
                case HTTP:
                  if (!(proxy.address() instanceof InetSocketAddress)) {
                    logger.warn("Don't know how to connect to address " + proxy.address());
                    return null;
                  } else {
                    InetSocketAddress address = (InetSocketAddress) proxy.address();
                    return proxyServer(address.getHostString(), address.getPort()).build();
                  }
                case DIRECT:
                  return null;
                default:
                  logger.warn("ProxySelector returned proxy type that we don't know how to use: " + proxy.type());
                  break;
              }
            }
          }
          return null;
        } catch (URISyntaxException e) {
          logger.warn(uri + " couldn't be turned into a java.net.URI", e);
          return null;
        }
    };
  }
}
