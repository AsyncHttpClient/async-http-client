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
package org.asynchttpclient.uri;

import static org.asynchttpclient.util.Assertions.assertNotNull;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import java.net.URI;
import java.net.URISyntaxException;

import org.asynchttpclient.util.MiscUtils;
import org.asynchttpclient.util.StringBuilderPool;

public class Uri {

    public static final String HTTP = "http";
    public static final String HTTPS = "https";
    public static final String WS = "ws";
    public static final String WSS = "wss";

    public static Uri create(String originalUrl) {
        return create(null, originalUrl);
    }

    public static Uri create(Uri context, final String originalUrl) {
        UriParser parser = new UriParser();
        parser.parse(context, originalUrl);

        return new Uri(parser.scheme,//
                parser.userInfo,//
                parser.host,//
                parser.port,//
                parser.path,//
                parser.query);
    }

    private final String scheme;
    private final String userInfo;
    private final String host;
    private final int port;
    private final String query;
    private final String path;
    private String url;
    private boolean secured;
    private boolean webSocket;

    public Uri(String scheme,//
            String userInfo,//
            String host,//
            int port,//
            String path,//
            String query) {

        this.scheme = assertNotNull(scheme, "scheme");
        this.userInfo = userInfo;
        this.host = assertNotNull(host, "host");
        this.port = port;
        this.path = path;
        this.query = query;
        this.secured = HTTPS.equals(scheme) || WSS.equals(scheme);
        this.webSocket = WS.equals(scheme) || WSS.equalsIgnoreCase(scheme);
    }

    public String getQuery() {
        return query;
    }

    public String getPath() {
        return path;
    }

    public String getUserInfo() {
        return userInfo;
    }

    public int getPort() {
        return port;
    }

    public String getScheme() {
        return scheme;
    }

    public String getHost() {
        return host;
    }

    public boolean isSecured() {
        return secured;
    }

    public boolean isWebSocket() {
        return webSocket;
    }

    public URI toJavaNetURI() throws URISyntaxException {
        return new URI(toUrl());
    }

    public int getExplicitPort() {
        return port == -1 ? getSchemeDefaultPort() : port;
    }

    public int getSchemeDefaultPort() {
        return isSecured() ? 443 : 80;
    }

    public String toUrl() {
        if (url == null) {
            StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
            sb.append(scheme).append("://");
            if (userInfo != null)
                sb.append(userInfo).append('@');
            sb.append(host);
            if (port != -1)
                sb.append(':').append(port);
            if (path != null)
                sb.append(path);
            if (query != null)
                sb.append('?').append(query);
            url = sb.toString();
            sb.setLength(0);
        }
        return url;
    }

    /**
     * @return [scheme]://[hostname](:[port]). Port is omitted if it matches the scheme's default one.
     */
    public String toBaseUrl() {
        StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
        sb.append(scheme).append("://").append(host);
        if (port != -1 && port != getSchemeDefaultPort()) {
            sb.append(':').append(port);
        }
        if (isNonEmpty(path)) {
            sb.append(path);
        }
        return sb.toString();
    }

    public String toRelativeUrl() {
        StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
        if (MiscUtils.isNonEmpty(path))
            sb.append(path);
        else
            sb.append('/');
        if (query != null)
            sb.append('?').append(query);

        return sb.toString();
    }

    @Override
    public String toString() {
        // for now, but might change
        return toUrl();
    }

    public Uri withNewScheme(String newScheme) {
        return new Uri(newScheme,//
                userInfo,//
                host,//
                port,//
                path,//
                query);
    }

    public Uri withNewQuery(String newQuery) {
        return new Uri(scheme,//
                userInfo,//
                host,//
                port,//
                path,//
                newQuery);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((host == null) ? 0 : host.hashCode());
        result = prime * result + ((path == null) ? 0 : path.hashCode());
        result = prime * result + port;
        result = prime * result + ((query == null) ? 0 : query.hashCode());
        result = prime * result + ((scheme == null) ? 0 : scheme.hashCode());
        result = prime * result + ((userInfo == null) ? 0 : userInfo.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Uri other = (Uri) obj;
        if (host == null) {
            if (other.host != null)
                return false;
        } else if (!host.equals(other.host))
            return false;
        if (path == null) {
            if (other.path != null)
                return false;
        } else if (!path.equals(other.path))
            return false;
        if (port != other.port)
            return false;
        if (query == null) {
            if (other.query != null)
                return false;
        } else if (!query.equals(other.query))
            return false;
        if (scheme == null) {
            if (other.scheme != null)
                return false;
        } else if (!scheme.equals(other.scheme))
            return false;
        if (userInfo == null) {
            if (other.userInfo != null)
                return false;
        } else if (!userInfo.equals(other.userInfo))
            return false;
        return true;
    }
}
