package org.asynchttpclient;

import org.asynchttpclient.uri.UriComponents;

/**
 * Selector for a proxy server
 */
public interface ProxyServerSelector {

    /**
     * Select a proxy server to use for the given URI.
     *
     * @param uri The URI to select a proxy server for.
     * @return The proxy server to use, if any.  May return null.
     */
    ProxyServer select(UriComponents uri);

    /**
     * A selector that always selects no proxy.
     */
    static final ProxyServerSelector NO_PROXY_SELECTOR = new ProxyServerSelector() {
        @Override
        public ProxyServer select(UriComponents uri) {
            return null;
        }
    };
}
