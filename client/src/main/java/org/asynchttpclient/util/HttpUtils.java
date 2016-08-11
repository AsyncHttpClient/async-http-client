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

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Param;
import org.asynchttpclient.Request;
import org.asynchttpclient.uri.Uri;

/**
 * {@link org.asynchttpclient.AsyncHttpClient} common utilities.
 */
public class HttpUtils {

    public final static Charset DEFAULT_CHARSET = ISO_8859_1;

    public static void validateSupportedScheme(Uri uri) {
        final String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("ws") && !scheme.equalsIgnoreCase("wss")) {
            throw new IllegalArgumentException("The URI scheme, of the URI " + uri + ", must be equal (ignoring case) to 'http', 'https', 'ws', or 'wss'");
        }
    }

    public static String getBaseUrl(Uri uri) {
        // getAuthority duplicate but we don't want to re-concatenate
        return uri.getScheme() + "://" + uri.getHost() + ":" + uri.getExplicitPort();
    }

    public static String getAuthority(Uri uri) {
        return uri.getHost() + ":" + uri.getExplicitPort();
    }

    public static boolean isSameBase(Uri uri1, Uri uri2) {
        return uri1.getScheme().equals(uri2.getScheme()) && uri1.getHost().equals(uri2.getHost()) && uri1.getExplicitPort() == uri2.getExplicitPort();
    }

    /**
     * @param uri the uri
     * @return the raw path or "/" if it's null
     */
    public static String getNonEmptyPath(Uri uri) {
        return isNonEmpty(uri.getPath()) ? uri.getPath() : "/";
    }

    public static Charset parseCharset(String contentType) {
        for (String part : contentType.split(";")) {
            if (part.trim().startsWith("charset=")) {
                String[] val = part.split("=");
                if (val.length > 1) {
                    String charset = val[1].trim();
                    // Quite a lot of sites have charset="CHARSET",
                    // e.g. charset="utf-8". Note the quotes. This is
                    // not correct, but client should be able to handle
                    // it (all browsers do, Grizzly strips it by default)
                    // This is a poor man's trim("\"").trim("'")
                    String charsetName = charset.replaceAll("\"", "").replaceAll("'", "");
                    return Charset.forName(charsetName);
                }
            }
        }
        return null;
    }

    public static boolean followRedirect(AsyncHttpClientConfig config, Request request) {
        return request.getFollowRedirect() != null ? request.getFollowRedirect() : config.isFollowRedirect();
    }

    private static StringBuilder urlEncodeFormParams0(List<Param> params) {
        StringBuilder sb = StringUtils.stringBuilder();
        for (Param param : params) {
            encodeAndAppendFormParam(sb, param.getName(), param.getValue());
        }
        sb.setLength(sb.length() - 1);
        return sb;
    }

    public static ByteBuffer urlEncodeFormParams(List<Param> params, Charset charset) {
        return StringUtils.charSequence2ByteBuffer(urlEncodeFormParams0(params), charset);
    }

    private static void encodeAndAppendFormParam(final StringBuilder sb, final CharSequence name, final CharSequence value) {
        Utf8UrlEncoder.encodeAndAppendFormElement(sb, name);
        if (value != null) {
            sb.append('=');
            Utf8UrlEncoder.encodeAndAppendFormElement(sb, value);
        }
        sb.append('&');
    }

    public static String hostHeader(Request request, Uri uri) {
        String virtualHost = request.getVirtualHost();
        if (virtualHost != null)
            return virtualHost;
        else {
            String host = uri.getHost();
            int port = uri.getPort();
            return port == -1 || port == uri.getSchemeDefaultPort() ? host : host + ":" + port;
        }
    }
}
