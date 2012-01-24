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

import com.ning.http.client.ProxyServer;
import com.ning.http.client.ProxyServer.Protocol;
import com.ning.http.client.Request;

import java.net.URI;
import java.util.List;
import java.util.Properties;

/**
 * Utilities for Proxy handling.
 *
 * @author cstamas
 */
public class ProxyUtils {

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

    /**
     * Checks whether proxy should be used according to nonProxyHosts settings of it, or we want to go directly to
     * target host. If <code>null</code> proxy is passed in, this method returns true -- since there is NO proxy, we
     * should avoid to use it. Simple hostname pattern matching using "*" are supported, but only as prefixes.
     * See http://download.oracle.com/javase/1.4.2/docs/guide/net/properties.html
     *
     * @param proxyServer
     * @param request
     * @return true if we have to avoid proxy use (obeying non-proxy hosts settings), false otherwise.
     */
    public static boolean avoidProxy(final ProxyServer proxyServer, final Request request) {
        return avoidProxy(proxyServer, AsyncHttpProviderUtils.getHost(URI.create(request.getUrl())));
    }

    /**
     * Checks whether proxy should be used according to nonProxyHosts settings of it, or we want to go directly to
     * target host. If <code>null</code> proxy is passed in, this method returns true -- since there is NO proxy, we
     * should avoid to use it. Simple hostname pattern matching using "*" are supported, but only as prefixes.
     * See http://download.oracle.com/javase/1.4.2/docs/guide/net/properties.html
     *
     * @param proxyServer
     * @param target      the hostname
     * @return true if we have to avoid proxy use (obeying non-proxy hosts settings), false otherwise.
     */
    public static boolean avoidProxy(final ProxyServer proxyServer, final String target) {
        if (proxyServer != null) {
            final String targetHost = target.toLowerCase();

            List<String> nonProxyHosts = proxyServer.getNonProxyHosts();

            if (nonProxyHosts != null && nonProxyHosts.size() > 0) {
                for (String nonProxyHost : nonProxyHosts) {
                    if (nonProxyHost.startsWith("*") && nonProxyHost.length() > 1
                            && targetHost.endsWith(nonProxyHost.substring(1).toLowerCase())) {
                        return true;
                    } else if (nonProxyHost.equalsIgnoreCase(targetHost)) {
                        return true;
                    }
                }
            }

            return false;
        } else {
            return true;
        }
    }

    /**
     * Creates a proxy server instance from the given properties.
     * <p/>
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
    public static ProxyServer createProxy(Properties properties) {
        String host = System.getProperty(PROXY_HOST);

        if (host != null) {
            int port = Integer.valueOf(System.getProperty(PROXY_PORT, "80"));

            Protocol protocol;
            try {
                protocol = Protocol.valueOf(System.getProperty(PROXY_PROTOCOL, "HTTP"));
            } catch (IllegalArgumentException e) {
                protocol = Protocol.HTTP;
            }

            ProxyServer proxyServer = new ProxyServer(protocol, host, port, System.getProperty(PROXY_USER), System.getProperty(PROXY_PASSWORD));

            String nonProxyHosts = System.getProperties().getProperty(PROXY_NONPROXYHOSTS);
            if (nonProxyHosts != null) {
                for (String spec : nonProxyHosts.split("\\|")) {
                    proxyServer.addNonProxyHost(spec);
                }
            }

            return proxyServer;
        }

        return null;
    }
}
