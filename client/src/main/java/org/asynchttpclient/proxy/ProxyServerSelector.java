package org.asynchttpclient.proxy;

import org.asynchttpclient.uri.Uri;

/**
 * Selector for choosing a proxy server based on the target URI.
 * <p>
 * This interface allows for flexible proxy selection strategies, such as selecting
 * different proxies based on the protocol, host, or other URI attributes. It can
 * also be used to implement proxy auto-configuration (PAC) or other dynamic proxy
 * selection mechanisms.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Always use the same proxy
 * ProxyServer proxy = new ProxyServer.Builder("proxy.example.com", 8080).build();
 * ProxyServerSelector selector = uri -> proxy;
 *
 * // Select proxy based on URI
 * ProxyServerSelector selector = uri -> {
 *     if (uri.isSecured()) {
 *         return secureProxy;
 *     } else {
 *         return standardProxy;
 *     }
 * };
 *
 * // No proxy
 * ProxyServerSelector selector = ProxyServerSelector.NO_PROXY_SELECTOR;
 * }</pre>
 */
public interface ProxyServerSelector {

  /**
   * A selector that always selects no proxy.
   * <p>
   * Use this selector when you want to bypass proxy configuration and connect
   * directly to all destinations.
   * </p>
   */
  ProxyServerSelector NO_PROXY_SELECTOR = uri -> null;

  /**
   * Selects a proxy server to use for the given URI.
   * <p>
   * Implementations can inspect the URI's protocol, host, port, or any other
   * attributes to determine which proxy to use, or whether to bypass the proxy
   * entirely.
   * </p>
   *
   * @param uri the URI to select a proxy server for
   * @return the proxy server to use, or null to connect directly without a proxy
   */
  ProxyServer select(Uri uri);
}
