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

import static com.ning.http.util.MiscUtils.isNonEmpty;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseBodyPartsInputStream;
import com.ning.http.client.Param;
import com.ning.http.client.Request;
import com.ning.http.client.uri.Uri;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.List;

/**
 * {@link com.ning.http.client.AsyncHttpProvider} common utilities.
 * <p/>
 * The cookies's handling code is from the Netty framework.
 */
public class AsyncHttpProviderUtils {

    public final static Charset DEFAULT_CHARSET = StandardCharsets.ISO_8859_1;

    static final byte[] EMPTY_BYTE_ARRAY = "".getBytes();

    public static final void validateSupportedScheme(Uri uri) {
        final String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("ws")
                && !scheme.equalsIgnoreCase("wss")) {
            throw new IllegalArgumentException("The URI scheme, of the URI " + uri
                    + ", must be equal (ignoring case) to 'http', 'https', 'ws', or 'wss'");
        }
    }

    public final static String getBaseUrl(Uri uri) {
        return uri.getScheme() + "://" + getAuthority(uri);
    }

    public final static String getAuthority(Uri uri) {
        int port = uri.getPort() != -1 ? uri.getPort() : getDefaultPort(uri);
        return uri.getHost() + ":" + port;
    }

    public final static String contentToString(List<HttpResponseBodyPart> bodyParts, Charset charset) throws UnsupportedEncodingException {
        return new String(contentToByte(bodyParts), charset);
    }

    public final static byte[] contentToByte(List<HttpResponseBodyPart> bodyParts) throws UnsupportedEncodingException {
        if (bodyParts.size() == 1) {
            return bodyParts.get(0).getBodyPartBytes();

        } else {
            int size = 0;
            for (HttpResponseBodyPart body : bodyParts) {
                size += body.getBodyPartBytes().length;
            }
            byte[] bytes = new byte[size];
            int offset = 0;
            for (HttpResponseBodyPart body : bodyParts) {
                byte[] bodyBytes = body.getBodyPartBytes();
                System.arraycopy(bodyBytes, 0, bytes, offset, bodyBytes.length);
                offset += bodyBytes.length;
            }

            return bytes;
        }
    }

    @SuppressWarnings("resource")
    public final static InputStream contentToInputStream(List<HttpResponseBodyPart> bodyParts) throws UnsupportedEncodingException {
        return bodyParts.isEmpty() ? new ByteArrayInputStream(EMPTY_BYTE_ARRAY) : new HttpResponseBodyPartsInputStream(bodyParts);
    }

    public final static int getDefaultPort(Uri uri) {
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
    public final static String getNonEmptyPath(Uri uri) {
        return isNonEmpty(uri.getPath()) ? uri.getPath() : "/";
    }

    public final static byte[] readFully(InputStream in, int[] lengthWrapper) throws IOException {
        // just in case available() returns bogus (or -1), allocate non-trivial chunk
        byte[] b = new byte[Math.max(512, in.available())];
        int offset = 0;
        while (true) {
            int left = b.length - offset;
            int count = in.read(b, offset, left);
            if (count < 0) { // EOF
                break;
            }
            offset += count;
            if (count == left) { // full buffer, need to expand
                b = doubleUp(b);
            }
        }
        // wish Java had Tuple return type...
        lengthWrapper[0] = offset;
        return b;
    }

    private static byte[] doubleUp(byte[] b) {
        int len = b.length;
        byte[] b2 = new byte[len + len];
        System.arraycopy(b, 0, b2, 0, len);
        return b2;
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
                    // it (all browsers do, Apache HTTP Client and Grizzly 
                    // strip it by default)
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
        return request.getRequestTimeout() != 0 ? request.getRequestTimeout() : config.getRequestTimeout();
    }

    public static boolean followRedirect(AsyncHttpClientConfig config, Request request) {
        return request.getFollowRedirect() != null ? request.getFollowRedirect().booleanValue() : config.isFollowRedirect();
    }

    public static String formParams2UTF8String(List<Param> params) {
        StringBuilder sb = new StringBuilder(params.size() * 15);
        for (Param param : params) {
            UTF8UrlEncoder.appendEncoded(sb, param.getName());
            sb.append("=");
            UTF8UrlEncoder.appendEncoded(sb, param.getValue());
            sb.append("&");
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
