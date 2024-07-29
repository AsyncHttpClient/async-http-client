/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.uri;

import org.asynchttpclient.util.StringBuilderPool;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;

import static org.asynchttpclient.util.Assertions.assertNotEmpty;
import static org.asynchttpclient.util.MiscUtils.isEmpty;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

public class Uri {

    public static final String HTTP = "http";
    public static final String HTTPS = "https";
    public static final String WS = "ws";
    public static final String WSS = "wss";
    private final String scheme;
    private final @Nullable String userInfo;
    private final String host;
    private final int port;
    private final @Nullable String query;
    private final String path;
    private final @Nullable String fragment;
    private @Nullable String url;
    private final boolean secured;
    private final boolean webSocket;

    public Uri(String scheme, @Nullable String userInfo, String host, int port, String path, @Nullable String query, @Nullable String fragment) {
        this.scheme = assertNotEmpty(scheme, "scheme");
        this.userInfo = userInfo;
        this.host = assertNotEmpty(host, "host");
        this.port = port;
        this.path = path;
        this.query = query;
        this.fragment = fragment;
        secured = HTTPS.equals(scheme) || WSS.equals(scheme);
        webSocket = WS.equals(scheme) || WSS.equalsIgnoreCase(scheme);
    }

    public static Uri create(String originalUrl) {
        return create(null, originalUrl);
    }

    public static Uri create(@Nullable Uri context, final String originalUrl) {
        UriParser parser = UriParser.parse(context, originalUrl);
        if (isEmpty(parser.scheme)) {
            throw new IllegalArgumentException(originalUrl + " could not be parsed into a proper Uri, missing scheme");
        }
        if (isEmpty(parser.host)) {
            throw new IllegalArgumentException(originalUrl + " could not be parsed into a proper Uri, missing host");
        }

        return new Uri(parser.scheme, parser.userInfo, parser.host, parser.port, parser.path, parser.query, parser.fragment);
    }

    public @Nullable String getQuery() {
        return query;
    }

    public String getPath() {
        return path;
    }

    public @Nullable String getUserInfo() {
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

    public @Nullable String getFragment() {
        return fragment;
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
            if (userInfo != null) {
                sb.append(userInfo).append('@');
            }
            sb.append(host);
            if (port != -1) {
                sb.append(':').append(port);
            }
            if (path != null) {
                sb.append(path);
            }
            if (query != null) {
                sb.append('?').append(query);
            }
            url = sb.toString();
            sb.setLength(0);
        }
        return url;
    }

    /**
     * @return [scheme]://[hostname](:[port])/path. Port is omitted if it matches the scheme's default one.
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
        if (isNonEmpty(path)) {
            sb.append(path);
        } else {
            sb.append('/');
        }
        if (query != null) {
            sb.append('?').append(query);
        }

        return sb.toString();
    }

    public String toFullUrl() {
        return fragment == null ? toUrl() : toUrl() + '#' + fragment;
    }

    public String getBaseUrl() {
        return scheme + "://" + host + ':' + getExplicitPort();
    }

    public String getAuthority() {
        return host + ':' + getExplicitPort();
    }

    public boolean isSameBase(Uri other) {
        return scheme.equals(other.getScheme())
                && host.equals(other.getHost())
                && getExplicitPort() == other.getExplicitPort();
    }

    public String getNonEmptyPath() {
        return isNonEmpty(path) ? path : "/";
    }

    @Override
    public String toString() {
        // for now, but might change
        return toUrl();
    }

    public Uri withNewScheme(String newScheme) {
        return new Uri(newScheme, userInfo, host, port, path, query, fragment);
    }

    public Uri withNewQuery(@Nullable String newQuery) {
        return new Uri(scheme, userInfo, host, port, path, newQuery, fragment);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (host == null ? 0 : host.hashCode());
        result = prime * result + (path == null ? 0 : path.hashCode());
        result = prime * result + port;
        result = prime * result + (query == null ? 0 : query.hashCode());
        result = prime * result + (scheme == null ? 0 : scheme.hashCode());
        result = prime * result + (userInfo == null ? 0 : userInfo.hashCode());
        result = prime * result + (fragment == null ? 0 : fragment.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        Uri other = (Uri) obj;
        if (host == null) {
            if (other.host != null) {
                return false;
            }
        } else if (!host.equals(other.host)) {
            return false;
        }
        if (path == null) {
            if (other.path != null) {
                return false;
            }
        } else if (!path.equals(other.path)) {
            return false;
        }
        if (port != other.port) {
            return false;
        }
        if (query == null) {
            if (other.query != null) {
                return false;
            }
        } else if (!query.equals(other.query)) {
            return false;
        }
        if (scheme == null) {
            if (other.scheme != null) {
                return false;
            }
        } else if (!scheme.equals(other.scheme)) {
            return false;
        }
        if (userInfo == null) {
            if (other.userInfo != null) {
                return false;
            }
        } else if (!userInfo.equals(other.userInfo)) {
            return false;
        }
        if (fragment == null) {
            return other.fragment == null;
        } else {
            return fragment.equals(other.fragment);
        }
    }

    public static void validateSupportedScheme(Uri uri) {
        final String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase(HTTP) && !scheme.equalsIgnoreCase(HTTPS) && !scheme.equalsIgnoreCase(WS) && !scheme.equalsIgnoreCase(WSS)) {
            throw new IllegalArgumentException("The URI scheme, of the URI " + uri + ", must be equal (ignoring case) to 'http', 'https', 'ws', or 'wss'");
        }
    }
}
