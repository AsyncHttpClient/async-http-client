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
package com.ning.http.util;


import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.ProxyServer.Protocol;
import com.ning.http.client.uri.Uri;
import com.ning.http.client.ProxyServerSelector;
import com.ning.http.client.Request;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities for Proxy handling.
 *
 * @author cstamas
 */
public final class ProxyUtils {

    private final static Logger log = LoggerFactory.getLogger(ProxyUtils.class);

    private static final String PROPERTY_PREFIX = "com.ning.http.client.AsyncHttpClientConfig.proxy.";

    /**
     * The host to use as proxy.
     */
    public static final String PROXY_HOST = "http.proxyHost";

    /**
     * The port to use for the proxy.
     */
    public static final String PROXY_PORT = "http.proxyPort";

    /**
     * The protocol to use. Is mapped to the {@link Protocol} enum.
     */
    public static final String PROXY_PROTOCOL = PROPERTY_PREFIX + "protocol";

    /**
     * A specification of non-proxy hosts. See http://download.oracle.com/javase/1.4.2/docs/guide/net/properties.html
     */
    public static final String PROXY_NONPROXYHOSTS = "http.nonProxyHosts";

    /**
     * The username to use for authentication for the proxy server.
     */
    public static final String PROXY_USER = PROPERTY_PREFIX + "user";

    /**
     * The password to use for authentication for the proxy server.
     */
    public static final String PROXY_PASSWORD = PROPERTY_PREFIX + "password";

    private ProxyUtils() {
    }
    
    /**
     * @param config the global config
     * @param request the request
     * @return the proxy server to be used for this request (can be null)
     */
    public static ProxyServer getProxyServer(AsyncHttpClientConfig config, Request request) {
        ProxyServer proxyServer = request.getProxyServer();
        if (proxyServer == null) {
            ProxyServerSelector selector = config.getProxyServerSelector();
            if (selector != null) {
                proxyServer = selector.select(request.getUri());
            }
        }
        return ProxyUtils.avoidProxy(proxyServer, request) ? null : proxyServer;
    }
    
    /**
     * @see #avoidProxy(ProxyServer, String)
     */
    public static boolean avoidProxy(final ProxyServer proxyServer, final Request request) {
        return avoidProxy(proxyServer, request.getUri().getHost());
    }

    private static boolean matchNonProxyHost(String targetHost, String nonProxyHost) {

        if (nonProxyHost.length() > 1) {
            if (nonProxyHost.charAt(0) == '*')
                return targetHost.regionMatches(true, targetHost.length() - nonProxyHost.length() + 1, nonProxyHost, 1,
                        nonProxyHost.length() - 1);
            else if (nonProxyHost.charAt(nonProxyHost.length() - 1) == '*')
                return targetHost.regionMatches(true, 0, nonProxyHost, 0, nonProxyHost.length() - 1);
        }

        return nonProxyHost.equalsIgnoreCase(targetHost);
    }
    
    /**
     * Checks whether proxy should be used according to nonProxyHosts settings of it, or we want to go directly to
     * target host. If <code>null</code> proxy is passed in, this method returns true -- since there is NO proxy, we
     * should avoid to use it. Simple hostname pattern matching using "*" are supported, but only as prefixes.
     * See http://download.oracle.com/javase/1.4.2/docs/guide/net/properties.html
     *
     * @param proxyServer
     * @param hostname      the hostname
     * @return true if we have to avoid proxy use (obeying non-proxy hosts settings), false otherwise.
     */
    public static boolean avoidProxy(final ProxyServer proxyServer, final String hostname) {
        if (proxyServer != null) {
            if (hostname == null)
                throw new NullPointerException("hostname");
            
            List<String> nonProxyHosts = proxyServer.getNonProxyHosts();

            if (nonProxyHosts != null) {
                for (String nonProxyHost : nonProxyHosts) {
                    if (matchNonProxyHost(hostname, nonProxyHost))
                        return true;
                }
            }

            return false;
        } else {
            return true;
        }
    }

    /**
     * Creates a proxy server instance from the given properties.
     * Currently the default http.* proxy properties are supported as well as properties specific for AHC.
     *
     * @param properties the properties to evaluate. Must not be null.
     * @return a ProxyServer instance or null, if no valid properties were set.
     * @see <a href="http://download.oracle.com/javase/1.4.2/docs/guide/net/properties.html">Networking Properties</a>
     * @see #PROXY_HOST
     * @see #PROXY_PORT
     * @see #PROXY_PROTOCOL
     * @see #PROXY_NONPROXYHOSTS
     */
    public static ProxyServerSelector createProxyServerSelector(Properties properties) {
        String host = properties.getProperty(PROXY_HOST);

        if (host != null) {
            int port = Integer.valueOf(properties.getProperty(PROXY_PORT, "80"));

            Protocol protocol;
            try {
                protocol = Protocol.valueOf(properties.getProperty(PROXY_PROTOCOL, "HTTP"));
            } catch (IllegalArgumentException e) {
                protocol = Protocol.HTTP;
            }

            ProxyServer proxyServer = new ProxyServer(protocol, host, port, properties.getProperty(PROXY_USER),
                    properties.getProperty(PROXY_PASSWORD));

            String nonProxyHosts = properties.getProperty(PROXY_NONPROXYHOSTS);
            if (nonProxyHosts != null) {
                for (String spec : nonProxyHosts.split("\\|")) {
                    proxyServer.addNonProxyHost(spec);
                }
            }

            return createProxyServerSelector(proxyServer);
        }

        return ProxyServerSelector.NO_PROXY_SELECTOR;
    }

    /**
     * Get a proxy server selector based on the JDK default proxy selector.
     *
     * @return The proxy server selector.
     */
    public static ProxyServerSelector getJdkDefaultProxyServerSelector() {
        return createProxyServerSelector(ProxySelector.getDefault());
    }

    /**
     * Create a proxy server selector based on the passed in JDK proxy selector.
     *
     * @param proxySelector The proxy selector to use.  Must not be null.
     * @return The proxy server selector.
     */
    public static ProxyServerSelector createProxyServerSelector(final ProxySelector proxySelector) {
        return new ProxyServerSelector() {
            public ProxyServer select(Uri uri) {
                try {
                    URI javaUri = uri.toJavaNetURI();

                    List<Proxy> proxies = proxySelector.select(javaUri);
                    if (proxies != null) {
                        // Loop through them until we find one that we know how to use
                        for (Proxy proxy : proxies) {
                            switch (proxy.type()) {
                            case HTTP:
                                if (!(proxy.address() instanceof InetSocketAddress)) {
                                    log.warn("Don't know how to connect to address " + proxy.address());
                                    return null;
                                } else {
                                    InetSocketAddress address = (InetSocketAddress) proxy.address();
                                    return new ProxyServer(Protocol.HTTP, address.getHostName(), address.getPort());
                                }
                            case DIRECT:
                                return null;
                            default:
                                log.warn("ProxySelector returned proxy type that we don't know how to use: " + proxy.type());
                                break;
                            }
                        }
                    }
                    return null;
                } catch (URISyntaxException e) {
                    log.warn(uri + " couldn't be turned into a java.net.URI", e);
                    return null;
                }
            }
        };
    }

    /**
     * Create a proxy server selector that always selects a single proxy server.
     *
     * @param proxyServer The proxy server to select.
     * @return The proxy server selector.
     */
    public static ProxyServerSelector createProxyServerSelector(final ProxyServer proxyServer) {
        return new ProxyServerSelector() {
            public ProxyServer select(Uri uri) {
                return proxyServer;
            }
        };
    }
}
