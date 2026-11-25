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

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AsciiString;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Param;
import org.asynchttpclient.Request;
import org.asynchttpclient.uri.Uri;
import org.jetbrains.annotations.Nullable;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * {@link AsyncHttpClient} common utilities.
 */
public final class HttpUtils {

    public static final AsciiString ACCEPT_ALL_HEADER_VALUE = new AsciiString("*/*");
    public static final AsciiString GZIP_DEFLATE = new AsciiString(HttpHeaderValues.GZIP + "," + HttpHeaderValues.DEFLATE);
    /** RFC 7541 Appendix A static table entry 16. */
    public static final AsciiString GZIP_DEFLATE_HPACK = new AsciiString(HttpHeaderValues.GZIP + ", " + HttpHeaderValues.DEFLATE);
    private static final String CONTENT_TYPE_CHARSET_ATTRIBUTE = "charset=";
    private static final String CONTENT_TYPE_BOUNDARY_ATTRIBUTE = "boundary=";

    private HttpUtils() {
        // Prevent outside initialization
    }

    public static String hostHeader(Uri uri) {
        String host = uri.getHost();
        int port = uri.getPort();
        return port == -1 || port == uri.getSchemeDefaultPort() ? host : host + ':' + port;
    }

    public static String originHeader(Uri uri) {
        StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
        sb.append(uri.isSecured() ? "https://" : "http://").append(uri.getHost());
        if (uri.getExplicitPort() != uri.getSchemeDefaultPort()) {
            sb.append(':').append(uri.getPort());
        }
        return sb.toString();
    }

    public static @Nullable Charset extractContentTypeCharsetAttribute(String contentType) {
        String charsetName = extractContentTypeAttribute(contentType, CONTENT_TYPE_CHARSET_ATTRIBUTE);
        return charsetName != null ? Charset.forName(charsetName) : null;
    }

    public static @Nullable String extractContentTypeBoundaryAttribute(String contentType) {
        return extractContentTypeAttribute(contentType, CONTENT_TYPE_BOUNDARY_ATTRIBUTE);
    }

    private static @Nullable String extractContentTypeAttribute(@Nullable String contentType, String attribute) {
        if (contentType == null) {
            return null;
        }

        for (int i = 0; i < contentType.length(); i++) {
            if (contentType.regionMatches(true, i, attribute, 0,
                    attribute.length())) {
                int start = i + attribute.length();

                // trim left
                while (start < contentType.length()) {
                    char c = contentType.charAt(start);
                    if (c == ' ' || c == '\'' || c == '"') {
                        start++;
                    } else {
                        break;
                    }
                }
                if (start == contentType.length()) {
                    break;
                }

                // trim right
                int end = start + 1;
                while (end < contentType.length()) {
                    char c = contentType.charAt(end);
                    if (c == ' ' || c == '\'' || c == '"' || c == ';') {
                        break;
                    } else {
                        end++;
                    }
                }

                return contentType.substring(start, end);
            }
        }

        return null;
    }

    // The pool of ASCII chars to be used for generating a multipart boundary.
    private static final byte[] MULTIPART_CHARS = "-_1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(US_ASCII);

    // The boundary is what separates parts whose content is not escaped, so it must be unpredictable,
    // like the cnonce in Realm and the SCRAM nonce.
    private static final ThreadLocal<SecureRandom> BOUNDARY_RANDOM = ThreadLocal.withInitial(SecureRandom::new);

    // a random size from 30 to 40
    public static byte[] computeMultipartBoundary() {
        SecureRandom random = BOUNDARY_RANDOM.get();
        byte[] bytes = new byte[random.nextInt(11) + 30];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = MULTIPART_CHARS[random.nextInt(MULTIPART_CHARS.length)];
        }
        return bytes;
    }

    public static String patchContentTypeWithBoundaryAttribute(String base, byte[] boundary) {
        StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder().append(base);
        if (!base.isEmpty() && base.charAt(base.length() - 1) != ';') {
            sb.append(';');
        }
        return sb.append(' ').append(CONTENT_TYPE_BOUNDARY_ATTRIBUTE).append(new String(boundary, US_ASCII)).toString();
    }

    public static boolean followRedirect(AsyncHttpClientConfig config, Request request) {
        return request.getFollowRedirect() != null ? request.getFollowRedirect() : config.isFollowRedirect();
    }

    public static boolean useAbsoluteRequestDeadline(AsyncHttpClientConfig config, Request request) {
        Boolean override = request.getUseAbsoluteRequestDeadline();
        return override != null ? override : config.isUseAbsoluteRequestDeadline();
    }

    /**
     * Returns the length that a request's {@code Content-Length} header gives, or -1 without one.
     * <p>
     * This is AHC's strict reading of the header for the requests it sends, not a general HTTP parser. A length is one
     * field of ASCII digits, without a sign (RFC 9110 section 8.6). Whitespace after the digits is not part of the
     * field value (RFC 9112 section 5.1). Whitespace in front fails Netty's validation of the request's headers, so it
     * is not a length here either. Nor is a list of identical values such as {@code 16, 16}: RFC 9110 lets a recipient
     * accept one, but a sender must not send it. A length past {@link Long#MAX_VALUE} is AHC's own limit, not the
     * RFC's.
     *
     * @param headers the headers to read the {@code Content-Length} from
     * @return the length, or -1 if there is no {@code Content-Length} header
     * @throws IllegalArgumentException if the header is not a length, or one longer than AHC supports
     */
    public static long parseContentLength(HttpHeaders headers) {
        // Looked up first: every request is checked, and getAll allocates its list.
        if (!headers.contains(CONTENT_LENGTH)) {
            return -1;
        }
        List<String> values = headers.getAll(CONTENT_LENGTH);
        String value = values.get(0);
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
            end--;
        }
        if (values.size() > 1 || end == 0) {
            throw new IllegalArgumentException("Content-Length header " + values + " is not a length");
        }
        long length = 0;
        boolean tooLong = false;
        for (int i = 0; i < end; i++) {
            int digit = value.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                throw new IllegalArgumentException("Content-Length header " + values + " is not a length");
            }
            if (tooLong || length > (Long.MAX_VALUE - digit) / 10) {
                tooLong = true;
            } else {
                length = length * 10 + digit;
            }
        }
        if (tooLong) {
            throw new IllegalArgumentException("Content-Length header " + values + " is longer than AHC supports");
        }
        return length;
    }

    public static ByteBuffer urlEncodeFormParams(List<Param> params, Charset charset) {
        return StringUtils.charSequence2ByteBuffer(urlEncodeFormParams0(params, charset), US_ASCII);
    }

    private static StringBuilder urlEncodeFormParams0(List<Param> params, Charset charset) {
        StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
        for (Param param : params) {
            encodeAndAppendFormParam(sb, param.getName(), param.getValue(), charset);
        }
        sb.setLength(sb.length() - 1);
        return sb;
    }

    private static void encodeAndAppendFormParam(StringBuilder sb, String name, @Nullable String value, Charset charset) {
        encodeAndAppendFormField(sb, name, charset);
        if (value != null) {
            sb.append('=');
            encodeAndAppendFormField(sb, value, charset);
        }
        sb.append('&');
    }

    private static void encodeAndAppendFormField(StringBuilder sb, String field, Charset charset) {
        if (charset.equals(UTF_8)) {
            Utf8UrlEncoder.encodeAndAppendFormElement(sb, field);
        } else {
            // TODO there's probably room for perf improvements
            sb.append(URLEncoder.encode(field, charset));
        }
    }

    /**
     * The name=value pairs of a {@code Cookie} header value, as written. Every ';' ends a pair, inside quotes too:
     * RFC 6265 does not allow one in a cookie value.
     */
    public static String[] cookiePairs(String cookieHeader) {
        return cookieHeader.split(";", -1);
    }

    /**
     * The name of a pair from {@link #cookiePairs}: the text before its first '=', or all of it, trimmed, so
     * "a = 1" names "a".
     */
    public static String cookiePairName(String pair) {
        int eq = pair.indexOf('=');
        return (eq >= 0 ? pair.substring(0, eq) : pair).trim();
    }

    // Decoded over HTTP/1.1 and HTTP/2 alike, unlike br and zstd: Http2ContentDecompressor decodes only these.
    private static final AsciiString[] WILDCARD_CODINGS = {HttpHeaderValues.GZIP, HttpHeaderValues.DEFLATE};

    public static CharSequence filterOutBrotliFromAcceptEncoding(String acceptEncoding) {
        return filterOutAcceptEncoding(acceptEncoding, HttpHeaderValues.BR);
    }

    public static CharSequence filterOutZstdFromAcceptEncoding(String acceptEncoding) {
        return filterOutAcceptEncoding(acceptEncoding, HttpHeaderValues.ZSTD);
    }

    /**
     * Drops every element offering {@code coding}, which may appear anywhere in the list, in any case and with a weight
     * (RFC 9110 section 12.5.3). Dropping the last element leaves an empty value, which accepts no content coding.
     * An element with weight 0 refuses the coding and is kept. A list with a "*" that accepts codings is rebuilt by
     * {@link #replaceWildcard}.
     */
    private static String filterOutAcceptEncoding(String acceptEncoding, AsciiString coding) {
        if (!AsciiString.containsIgnoreCase(acceptEncoding, coding) && acceptEncoding.indexOf('*') < 0) {
            return acceptEncoding;
        }
        String[] elements = acceptEncoding.split(",");
        for (String element : elements) {
            if (offers(element.trim(), "*")) {
                return replaceWildcard(elements, coding);
            }
        }
        StringBuilder kept = new StringBuilder(acceptEncoding.length());
        boolean dropped = false;
        for (String element : elements) {
            String trimmed = element.trim();
            if (offers(trimmed, coding)) {
                dropped = true;
            } else if (!trimmed.isEmpty()) {
                appendElement(kept, trimmed);
            }
        }
        return dropped ? kept.toString() : acceptEncoding;
    }

    /**
     * A "*" would still offer {@code coding}, so it is dropped, with every element naming {@code coding} and every
     * other token holding a "*". A coding the list does not name is then not acceptable, so the refusals are dropped
     * too, but "identity;q=0" and "*;q=0", which decide whether an unencoded response is acceptable. When no content
     * coding but br or zstd is left with a positive weight and no "*;q=0" refuses the codings the list does not name,
     * the {@link #WILDCARD_CODINGS} the list does not name take the place of the "*", at its weight. Only the weight is
     * carried over, as a parameter of the "*" could name a coding.
     * <p>
     * Netty's {@code HttpContentCompressor} matches "*" and coding names by substring, picks a refused coding once a
     * listed coding has a positive weight, and compares weights along br, zstd, snappy, gzip, deflate. Keeping the
     * elements with a positive weight as they are, and adding gzip and deflate only when nothing else is offered,
     * leaves it the choice it makes on the unfiltered list, unless that choice was {@code coding}, a refused coding or
     * one offered only by the "*".
     */
    private static String replaceWildcard(String[] elements, AsciiString coding) {
        String weight = "";
        boolean wildcardSeen = false;
        boolean offersOther = false;
        boolean refusesOthers = false;
        StringBuilder kept = new StringBuilder();
        for (String element : elements) {
            String trimmed = element.trim();
            String token = token(trimmed);
            if (offers(trimmed, "*")) {
                if (!wildcardSeen) {
                    wildcardSeen = true;
                    weight = weight(trimmed);
                }
            } else if ("*".equals(token) || AsciiString.contentEqualsIgnoreCase(token, HttpHeaderValues.IDENTITY)) {
                appendElement(kept, trimmed);
                refusesOthers |= "*".equals(token);
            } else if (!trimmed.isEmpty() && token.indexOf('*') < 0 && !refuses(trimmed)
                    && !AsciiString.contentEqualsIgnoreCase(token, coding)) {
                appendElement(kept, trimmed);
                offersOther |= !AsciiString.contentEqualsIgnoreCase(token, HttpHeaderValues.BR)
                        && !AsciiString.contentEqualsIgnoreCase(token, HttpHeaderValues.ZSTD);
            }
        }
        if (!offersOther && !refusesOthers) {
            for (AsciiString wildcardCoding : WILDCARD_CODINGS) {
                if (!names(elements, wildcardCoding)) {
                    appendElement(kept, wildcardCoding + weight);
                }
            }
        }
        return kept.toString();
    }

    private static String token(String element) {
        int semicolon = element.indexOf(';');
        return semicolon < 0 ? element : element.substring(0, semicolon).trim();
    }

    private static String weight(String element) {
        int semicolon = element.indexOf(';');
        if (semicolon >= 0) {
            for (String parameter : element.substring(semicolon + 1).split(";")) {
                int eq = parameter.indexOf('=');
                if (eq >= 0 && "q".equalsIgnoreCase(parameter.substring(0, eq).trim())) {
                    return ";q=" + parameter.substring(eq + 1).trim();
                }
            }
        }
        return "";
    }

    private static boolean offers(String element, CharSequence coding) {
        return AsciiString.contentEqualsIgnoreCase(token(element), coding) && !refuses(element);
    }

    private static boolean refuses(String element) {
        int semicolon = element.indexOf(';');
        return semicolon >= 0 && hasZeroWeight(element.substring(semicolon + 1));
    }

    // A recipient should treat x-gzip as gzip (RFC 9110 section 8.4.1.3); AHC decodes x-deflate as deflate.
    private static boolean names(String[] elements, AsciiString coding) {
        AsciiString alias = HttpHeaderValues.GZIP.equals(coding) ? HttpHeaderValues.X_GZIP : HttpHeaderValues.X_DEFLATE;
        for (String element : elements) {
            String token = token(element.trim());
            if (AsciiString.contentEqualsIgnoreCase(token, coding)
                    || AsciiString.contentEqualsIgnoreCase(token, alias)) {
                return true;
            }
        }
        return false;
    }

    private static void appendElement(StringBuilder list, String element) {
        if (list.length() > 0) {
            list.append(", ");
        }
        list.append(element);
    }

    private static boolean hasZeroWeight(String parameters) {
        for (String parameter : parameters.split(";")) {
            int eq = parameter.indexOf('=');
            if (eq >= 0 && "q".equalsIgnoreCase(parameter.substring(0, eq).trim())) {
                String weight = parameter.substring(eq + 1).trim();
                if (weight.isEmpty() || weight.charAt(0) != '0') {
                    return false;
                }
                for (int i = 1; i < weight.length(); i++) {
                    char c = weight.charAt(i);
                    if (c != '0' && (c != '.' || i != 1)) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }
}
