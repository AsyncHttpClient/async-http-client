/*
 * Copyright (c) 2010-2015 Sonatype, Inc. All rights reserved.
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

import static com.ning.http.util.MiscUtils.*;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

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
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;

/**
 * {@link com.ning.http.client.AsyncHttpProvider} common utilities.
 * <p/>
 * The cookies's handling code is from the Netty framework.
 */
public class AsyncHttpProviderUtils {

    public static final IOException REMOTELY_CLOSED_EXCEPTION = buildStaticIOException("Remotely closed");

    public final static Charset DEFAULT_CHARSET = ISO_8859_1;

    public static final String HTTP = "http";
    public static final String HTTPS = "https";
    public static final String WEBSOCKET = "ws";
    public static final String WEBSOCKET_SSL = "wss";

    static final byte[] EMPTY_BYTE_ARRAY = "".getBytes();

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

    public final static InputStream contentToInputStream(List<HttpResponseBodyPart> bodyParts) throws UnsupportedEncodingException {
        return bodyParts.isEmpty() ? new ByteArrayInputStream(EMPTY_BYTE_ARRAY) : new HttpResponseBodyPartsInputStream(bodyParts);
    }

    public final static boolean isSameHostAndProtocol(Uri uri1, Uri uri2) {
        return uri1.getScheme().equals(uri2.getScheme()) && uri1.getHost().equals(uri2.getHost())
                && getDefaultPort(uri1) == getDefaultPort(uri2);
    }

    public static final int getSchemeDefaultPort(String scheme) {
        return scheme.equals("http") || scheme.equals("ws") ? 80 : 443;
    }

    public static final int getDefaultPort(Uri uri) {
        int port = uri.getPort();
        if (port == -1)
            port = getSchemeDefaultPort(uri.getScheme());
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

    public static String connectionHeader(boolean allowConnectionPooling, boolean http11) {
        if (allowConnectionPooling)
            return "keep-alive";
        else if (http11)
            return "close";
        else
            return null;
    }

    public static int requestTimeout(AsyncHttpClientConfig config, Request request) {
        return request.getRequestTimeout() != 0 ? request.getRequestTimeout() : config.getRequestTimeout();
    }

    public static boolean followRedirect(AsyncHttpClientConfig config, Request request) {
        return request.getFollowRedirect() != null ? request.getFollowRedirect().booleanValue() : config.isFollowRedirect();
    }

    public static StringBuilder urlEncodeFormParams0(List<Param> params) {
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
        UTF8UrlEncoder.encodeAndAppendFormElement(sb, name);
        if (value != null) {
            sb.append('=');
            UTF8UrlEncoder.encodeAndAppendFormElement(sb, value);
        }
        sb.append('&');
    }

    public static String getNTLM(List<String> authenticateHeaders) {
        if (MiscUtils.isNonEmpty(authenticateHeaders)) {
            for (String authenticateHeader : authenticateHeaders) {
                if (authenticateHeader.startsWith("NTLM"))
                    return authenticateHeader;
            }
        }

        return null;
    }

    public static boolean isWebSocket(String scheme) {
        return WEBSOCKET.equals(scheme) || WEBSOCKET_SSL.equalsIgnoreCase(scheme);
    }

    public static boolean isSecure(String scheme) {
        return HTTPS.equals(scheme) || WEBSOCKET_SSL.equals(scheme);
    }

    public static boolean isSecure(Uri uri) {
        return isSecure(uri.getScheme());
    }

    public static boolean useProxyConnect(Uri uri) {
        return isSecure(uri) || isWebSocket(uri.getScheme());
    }
}
