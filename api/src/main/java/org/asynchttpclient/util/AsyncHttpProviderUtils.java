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

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.Request;
import org.asynchttpclient.uri.UriComponents;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.List;

/**
 * {@link org.asynchttpclient.AsyncHttpProvider} common utilities.
 */
public class AsyncHttpProviderUtils {

    public static final IOException REMOTELY_CLOSED_EXCEPTION = new IOException("Remotely Closed");

    static {
        REMOTELY_CLOSED_EXCEPTION.setStackTrace(new StackTraceElement[] {});
    }

    private final static byte[] NO_BYTES = new byte[0];

    public final static Charset DEFAULT_CHARSET = StandardCharsets.ISO_8859_1;

    public static final void validateSupportedScheme(UriComponents uri) {
        final String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("ws")
                && !scheme.equalsIgnoreCase("wss")) {
            throw new IllegalArgumentException("The URI scheme, of the URI " + uri
                    + ", must be equal (ignoring case) to 'http', 'https', 'ws', or 'wss'");
        }
    }

    /**
     * @param bodyParts NON EMPTY body part
     * @param maxLen
     * @return
     * @throws UnsupportedEncodingException
     */
    public final static byte[] contentToBytes(List<HttpResponseBodyPart> bodyParts, int maxLen) throws UnsupportedEncodingException {
        final int partCount = bodyParts.size();
        if (partCount == 0) {
            return NO_BYTES;
        }
        if (partCount == 1) {
            byte[] chunk = bodyParts.get(0).getBodyPartBytes();
            if (chunk.length <= maxLen) {
                return chunk;
            }
            byte[] result = new byte[maxLen];
            System.arraycopy(chunk, 0, result, 0, maxLen);
            return result;
        }
        int size = 0;
        byte[] result = new byte[maxLen];
        for (HttpResponseBodyPart part : bodyParts) {
            byte[] chunk = part.getBodyPartBytes();
            int amount = Math.min(maxLen - size, chunk.length);
            System.arraycopy(chunk, 0, result, size, amount);
            size += amount;
            if (size == maxLen) {
                return result;
            }
        }
        if (size < maxLen) {
            byte[] old = result;
            result = new byte[old.length];
            System.arraycopy(old, 0, result, 0, old.length);
        }
        return result;
    }

    public final static String getBaseUrl(UriComponents uri) {
        return uri.getScheme() + "://" + getAuthority(uri);
    }

    public final static String getAuthority(UriComponents uri) {
        int port = uri.getPort() != -1? uri.getPort() : getDefaultPort(uri);
        return uri.getHost() + ":" + port;
    }

    public final static int getDefaultPort(UriComponents uri) {
        int port = uri.getPort();
        if (port == -1)
            port = uri.getScheme().equals("http") || uri.getScheme().equals("ws") ? 80 : 443;
        return port;
    }

    /**
     * Convenient for HTTP layer when targeting server root
     * 
     * @return the raw path or "/" if it's null
     */
    public final static String getNonEmptyPath(UriComponents uri) {
        return isNonEmpty(uri.getPath()) ? uri.getPath() : "/";
    }

    public static String constructUserAgent(Class<? extends AsyncHttpProvider> httpProvider, AsyncHttpClientConfig config) {
        return new StringBuilder("AHC (").append(httpProvider.getSimpleName()).append(" - ").append(System.getProperty("os.name"))
                .append(" - ").append(System.getProperty("os.version")).append(" - ").append(System.getProperty("java.version"))
                .append(" - ").append(Runtime.getRuntime().availableProcessors()).append(" core(s))").toString();
    }

    public static String parseCharset(String contentType) {
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
                    return charset.replaceAll("\"", "").replaceAll("'", "");
                }
            }
        }
        return null;
    }

    public static String keepAliveHeaderValue(AsyncHttpClientConfig config) {
        return config.isAllowPoolingConnections() ? "keep-alive" : "close";
    }

    public static int requestTimeout(AsyncHttpClientConfig config, Request request) {
        return request.getRequestTimeoutInMs() != 0 ? request.getRequestTimeoutInMs() : config.getRequestTimeout();
    }

    public static boolean followRedirect(AsyncHttpClientConfig config, Request request) {
        return request.getFollowRedirect() != null? request.getFollowRedirect().booleanValue() : config.isFollowRedirect();
    }
}
