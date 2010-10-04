/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
/*
 * Copyright 2009 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.util;

import com.ning.http.client.ByteArrayPart;
import com.ning.http.client.Cookie;
import com.ning.http.client.FilePart;
import com.ning.http.client.FluentStringsMap;
import com.ning.http.client.Part;
import com.ning.http.client.StringPart;
import com.ning.http.multipart.ByteArrayPartSource;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.multipart.PartSource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collection;
import java.util.List;

/**
 * {@link com.ning.http.client.AsyncHttpProvider} common utilities.
 *
 * The cookies's handling code is from the Netty framework.
 */
public class AsyncHttpProviderUtils {
    //space ' '
    static final byte SP = 32;

    //tab ' '
    static final byte HT = 9;

    /**
     * Carriage return
     */
    static final byte CR = 13;

    /**
     * Equals '='
     */
    static final byte EQUALS = 61;

    /**
     * Line feed character
     */
    static final byte LF = 10;

    /**
     * carriage return line feed
     */
    static final byte[] CRLF = new byte[]{CR, LF};

    /**
     * Colon ':'
     */
    static final byte COLON = 58;

    /**
     * Semicolon ';'
     */
    static final byte SEMICOLON = 59;

    /**
     * comma ','
     */
    static final byte COMMA = 44;

    static final byte DOUBLE_QUOTE = '"';

    static final String PATH = "Path";

    static final String EXPIRES = "Expires";

    static final String MAX_AGE = "Max-Age";

    static final String DOMAIN = "Domain";

    static final String SECURE = "Secure";

    static final String HTTPONLY = "HTTPOnly";

    static final String COMMENT = "Comment";

    static final String COMMENTURL = "CommentURL";

    static final String DISCARD = "Discard";

    static final String PORT = "Port";

    static final String VERSION = "Version";

    public final static URI createUri(String u) {
        URI uri = URI.create(u);
        final String scheme = uri.getScheme().toLowerCase();
        if (scheme == null || !scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("The URI scheme, of the URI " + u
                    + ", must be equal (ignoring case) to 'http'");
        }

        String path = uri.getPath();
        if (path == null) {
            throw new IllegalArgumentException("The URI path, of the URI " + uri
                    + ", must be non-null");
        } else if (path.length() > 0 && path.charAt(0) != '/') {
            throw new IllegalArgumentException("The URI path, of the URI " + uri
                    + ". must start with a '/'");
        } else if (path.length() == 0) {
            return URI.create(u + "/");
        }

        return uri;
    }

    public final static String getBaseUrl(URI uri) {
        String url = uri.getScheme() + "://" + uri.getAuthority();
        int port = uri.getPort();
        if (port == -1) {
            port = getPort(uri);
            url += ":" + port;
        }
        return url;
    }

    public final static int getPort(URI uri) {
        int port = uri.getPort();
        if (port == -1)
            port = uri.getScheme().equals("http") ? 80 : 443;
        return port;
    }

    /**
     * This is quite ugly as our internal names are duplicated, but we build on top of HTTP Client implementation.
     *
     * @param params
     * @param methodParams
     * @return
     * @throws java.io.FileNotFoundException
     */
    public final static MultipartRequestEntity createMultipartRequestEntity(List<Part> params, FluentStringsMap methodParams) throws FileNotFoundException {
        com.ning.http.multipart.Part[] parts = new com.ning.http.multipart.Part[params.size()];
        int i = 0;

        for (Part part : params) {
            if (part instanceof StringPart) {
                parts[i] = new com.ning.http.multipart.StringPart(part.getName(),
                        ((StringPart) part).getValue(),
                        "UTF-8");
            } else if (part instanceof FilePart) {
                parts[i] = new com.ning.http.multipart.FilePart(part.getName(),
                        ((FilePart) part).getFile(),
                        ((FilePart) part).getMimeType(),
                        ((FilePart) part).getCharSet());

            } else if (part instanceof ByteArrayPart) {
                PartSource source = new ByteArrayPartSource(((ByteArrayPart) part).getFileName(), ((ByteArrayPart) part).getData());
                parts[i] = new com.ning.http.multipart.FilePart(part.getName(),
                        source,
                        ((ByteArrayPart) part).getMimeType(),
                        ((ByteArrayPart) part).getCharSet());

            } else if (part == null) {
                throw new NullPointerException("Part cannot be null");
            } else {
                throw new IllegalArgumentException(String.format("[" + Thread.currentThread().getName() + "] Unsupported part type for multipart parameter %s",
                        part.getName()));
            }
            ++i;
        }
        return new MultipartRequestEntity(parts, methodParams);
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

    public static String encodeCookies(Collection<Cookie> cookies) {
        StringBuilder sb = new StringBuilder();

        for (Cookie cookie : cookies) {
            if (cookie.getVersion() >= 1) {
                add(sb, '$' + VERSION, 1);
            }

            add(sb, cookie.getName(), cookie.getValue());

            if (cookie.getPath() != null) {
                add(sb, '$' + PATH, cookie.getPath());
            }

            if (cookie.getDomain() != null) {
                add(sb, '$' + DOMAIN, cookie.getDomain());
            }

            if (cookie.getVersion() >= 1) {
                if (!cookie.getPorts().isEmpty()) {
                    sb.append('$');
                    sb.append(PORT);
                    sb.append((char) EQUALS);
                    sb.append((char) DOUBLE_QUOTE);
                    for (int port : cookie.getPorts()) {
                        sb.append(port);
                        sb.append((char) COMMA);
                    }
                    sb.setCharAt(sb.length() - 1, (char) DOUBLE_QUOTE);
                    sb.append((char) SEMICOLON);
                }
            }
        }

        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private static void add(StringBuilder sb, String name, String val) {
        if (val == null) {
            addQuoted(sb, name, "");
            return;
        }

        for (int i = 0; i < val.length(); i++) {
            char c = val.charAt(i);
            switch (c) {
                case '\t':
                case ' ':
                case '"':
                case '(':
                case ')':
                case ',':
                case '/':
                case ':':
                case ';':
                case '<':
                case '=':
                case '>':
                case '?':
                case '@':
                case '[':
                case '\\':
                case ']':
                case '{':
                case '}':
                    addQuoted(sb, name, val);
                    return;
            }
        }

        addUnquoted(sb, name, val);
    }

    private static void addUnquoted(StringBuilder sb, String name, String val) {
        sb.append(name);
        sb.append((char) EQUALS);
        sb.append(val);
        sb.append((char) SEMICOLON);
    }

    private static void addQuoted(StringBuilder sb, String name, String val) {
        if (val == null) {
            val = "";
        }

        sb.append(name);
        sb.append((char) EQUALS);
        sb.append((char) DOUBLE_QUOTE);
        sb.append(val.replace("\\", "\\\\").replace("\"", "\\\""));
        sb.append((char) DOUBLE_QUOTE);
        sb.append((char) SEMICOLON);
    }

    private static void add(StringBuilder sb, String name, int val) {
        sb.append(name);
        sb.append((char) EQUALS);
        sb.append(val);
        sb.append((char) SEMICOLON);
    }
}
