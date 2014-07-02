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

import static com.ning.http.util.MiscUtil.isNonEmpty;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.ByteArrayPart;
import com.ning.http.client.FilePart;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseBodyPartsInputStream;
import com.ning.http.client.Part;
import com.ning.http.client.Request;
import com.ning.http.client.StringPart;
import com.ning.http.multipart.ByteArrayPartSource;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.multipart.PartSource;

/**
 * {@link com.ning.http.client.AsyncHttpProvider} common utilities.
 * <p/>
 * The cookies's handling code is from the Netty framework.
 */
public class AsyncHttpProviderUtils {

    public final static String DEFAULT_CHARSET = "ISO-8859-1";

    static final byte[] EMPTY_BYTE_ARRAY = "".getBytes();
    
    public static final void validateSupportedScheme(URI uri) {
        final String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("ws")
                && !scheme.equalsIgnoreCase("wss")) {
            throw new IllegalArgumentException("The URI scheme, of the URI " + uri
                    + ", must be equal (ignoring case) to 'http', 'https', 'ws', or 'wss'");
        }
    }

    public final static URI createNonEmptyPathURI(String u) {
        URI uri = URI.create(u);
        validateSupportedScheme(uri);

        String path = uri.getPath();
        if (path == null) {
            throw new IllegalArgumentException("The URI path, of the URI " + uri  + ", must be non-null");
        } else if (isNonEmpty(path) && path.charAt(0) != '/') {
            throw new IllegalArgumentException("The URI path, of the URI " + uri  + ". must start with a '/'");
        } else if (!isNonEmpty(path)) {
            return URI.create(u + "/");
        }

        return uri;
    }

    public final static String getBaseUrl(URI uri) {
        return uri.getScheme() + "://" + getAuthority(uri);
    }

    public final static String getAuthority(URI uri) {
        String url = uri.getAuthority();
        int port = uri.getPort();
        if (port == -1) {
            port = getPort(uri);
            url += ":" + port;
        }
        return url;
    }

    public final static String contentToString(List<HttpResponseBodyPart> bodyParts, String charset) throws UnsupportedEncodingException {
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
        return bodyParts.isEmpty()? new ByteArrayInputStream(EMPTY_BYTE_ARRAY) : new HttpResponseBodyPartsInputStream(bodyParts);
    }

    public final static String getHost(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            host = uri.getAuthority();
        }
        return host;
    }

    public final static URI getRedirectUri(URI uri, String location) {
            if(location == null)
                throw new IllegalArgumentException("URI " + uri + " was redirected to null location");
            
            URI locationURI = null;
            try {
                locationURI = new URI(location);
            } catch (URISyntaxException e) {
                // rich, we have a badly encoded location, let's try to encode the query params
                String[] parts = location.split("\\?");
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Don't know how to turn this location into a proper URI:" + location, e);
                } else {
                    StringBuilder properUrl = new StringBuilder(location.length()).append(parts[0]).append("?");
                    
                    String[] queryParams = parts[1].split("&");
                    for (int i = 0; i < queryParams.length; i++) {
                        String queryParam = queryParams[i];
                        if (i != 0)
                            properUrl.append("&");
                        String[] nameValue = queryParam.split("=", 2);
                        UTF8UrlEncoder.appendEncoded(properUrl, nameValue[0]);
                        if (nameValue.length == 2) {
                            properUrl.append("=");
                            UTF8UrlEncoder.appendEncoded(properUrl, nameValue[1]);
                        }
                    }
                    
                    locationURI = URI.create(properUrl.toString());
                }
            }
            
            URI redirectUri = uri.resolve(locationURI);

            String scheme = redirectUri.getScheme();

            if (scheme == null || !scheme.equalsIgnoreCase("http")
                    && !scheme.equalsIgnoreCase("https")
                    && !scheme.equals("ws")
                    && !scheme.equals("wss")) {
                throw new IllegalArgumentException("The URI scheme, of the URI " + redirectUri
                        + ", must be equal (ignoring case) to 'ws, 'wss', 'http', or 'https'");
            }

            return redirectUri.normalize();
        }

    public final static int getPort(URI uri) {
        int port = uri.getPort();
        if (port == -1)
            port = uri.getScheme().equals("http") || uri.getScheme().equals("ws") ? 80 : 443;
        return port;
    }

    /**
     * This is quite ugly as our internal names are duplicated, but we build on top of HTTP Client implementation.
     *
     * @param params
     * @param requestHeaders
     * @return a MultipartRequestEntity.
     * @throws java.io.FileNotFoundException
     */
    public final static MultipartRequestEntity createMultipartRequestEntity(List<Part> params, FluentCaseInsensitiveStringsMap requestHeaders) throws FileNotFoundException {
        com.ning.http.multipart.Part[] parts = new com.ning.http.multipart.Part[params.size()];
        int i = 0;

        for (Part part : params) {
            if (part instanceof com.ning.http.multipart.Part) {
                parts[i] = (com.ning.http.multipart.Part) part;

            } else if (part instanceof StringPart) {
                StringPart stringPart = (StringPart) part;
                parts[i] = new com.ning.http.multipart.StringPart(part.getName(), stringPart.getValue(), stringPart.getCharset());

            } else if (part instanceof FilePart) {
                FilePart filePart = (FilePart) part;
                parts[i] = new com.ning.http.multipart.FilePart(part.getName(), filePart.getFile(), filePart.getMimeType(), filePart.getCharSet());

            } else if (part instanceof ByteArrayPart) {
                ByteArrayPart byteArrayPart = (ByteArrayPart) part;
                PartSource source = new ByteArrayPartSource(byteArrayPart.getFileName(), byteArrayPart.getData());
                parts[i] = new com.ning.http.multipart.FilePart(part.getName(), source, byteArrayPart.getMimeType(), byteArrayPart.getCharSet());

            } else if (part == null) {
                throw new NullPointerException("Part cannot be null");
 
            } else {
                throw new IllegalArgumentException(String.format("Unsupported part type for multipart parameter %s",
                        part.getName()));
            }
            ++i;
        }
        return new MultipartRequestEntity(parts, requestHeaders);
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

    public static String constructUserAgent(Class<? extends AsyncHttpProvider> httpProvider) {
        StringBuilder b = new StringBuilder("AsyncHttpClient/1.0")
                .append(" ")
                .append("(")
                .append(httpProvider.getSimpleName())
                .append(" - ")
                .append(System.getProperty("os.name"))
                .append(" - ")
                .append(System.getProperty("os.version"))
                .append(" - ")
                .append(System.getProperty("java.version"))
                .append(" - ")
                .append(Runtime.getRuntime().availableProcessors())
                .append(" core(s))");
        return b.toString();
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
        return config.getAllowPoolingConnection() ? "keep-alive" : "close";
    }
    
    public static int requestTimeout(AsyncHttpClientConfig config, Request request) {
        return (request.getPerRequestConfig() != null && request.getPerRequestConfig().getRequestTimeoutInMs() != 0) ? request.getPerRequestConfig().getRequestTimeoutInMs() : config.getRequestTimeoutInMs();
    }
}
