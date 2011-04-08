/*
 * Copyright (c) 2010-2011 Sonatype, Inc. All rights reserved.
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
        return avoidProxy(proxyServer, URI.create(request.getUrl()).getHost());
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
     * 
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
     * Currently only the default http.* proxy properties are supported. See
     * http://download.oracle.com/javase/1.4.2/docs/guide/net/properties.html
     *
     * @param properties
     *            the properties to evaluate. Must not be null.
     * @return a ProxyServer instance or null, if no valid properties were set.
     */
    public static ProxyServer createProxy(Properties properties) {
        ProxyServer proxyServer = null;
        String httpProxyHost = properties.getProperty("http.proxyHost");
        if (httpProxyHost != null) {
            proxyServer = new ProxyServer(httpProxyHost, Integer.valueOf(properties.getProperty("http.proxyPort", "80")));
            String nonProxyHosts = properties.getProperty("http.nonProxyHosts");
            if (nonProxyHosts != null) {
                for (String spec : nonProxyHosts.split("\\|")) {
                    proxyServer.addNonProxyHost(spec);
                }
            }
        }
        return proxyServer;
    }
}
