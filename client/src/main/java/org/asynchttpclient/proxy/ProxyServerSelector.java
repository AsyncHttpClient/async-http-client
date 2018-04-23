package org.asynchttpclient.proxy;

import org.asynchttpclient.uri.Uri;

/**
 * Selector for a proxy server
 */
public interface ProxyServerSelector {

  /**
   * A selector that always selects no proxy.
   */
  ProxyServerSelector NO_PROXY_SELECTOR = uri -> null;

  /**
   * Select a proxy server to use for the given URI.
   *
   * @param uri The URI to select a proxy server for.
   * @return The proxy server to use, if any.  May return null.
   */
  ProxyServer select(Uri uri);
}
