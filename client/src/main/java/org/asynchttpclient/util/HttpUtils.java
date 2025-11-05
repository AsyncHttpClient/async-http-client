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
import io.netty.util.AsciiString;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Param;
import org.asynchttpclient.Request;
import org.asynchttpclient.uri.Uri;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static java.nio.charset.StandardCharsets.*;

/**
 * Common utility methods for HTTP operations.
 * <p>
 * This class provides various helper methods for working with HTTP headers, URIs, encoding,
 * and content types. It includes utilities for URL encoding, multipart boundaries, header
 * parsing, and form parameter encoding.
 * </p>
 */
public class HttpUtils {

  /**
   * Header value for accepting all content types ("*&#47;*").
   */
  public static final AsciiString ACCEPT_ALL_HEADER_VALUE = new AsciiString("*/*");

  /**
   * Header value for gzip and deflate compression encoding.
   */
  public static final AsciiString GZIP_DEFLATE = new AsciiString(HttpHeaderValues.GZIP + "," + HttpHeaderValues.DEFLATE);

  private static final String CONTENT_TYPE_CHARSET_ATTRIBUTE = "charset=";

  private static final String CONTENT_TYPE_BOUNDARY_ATTRIBUTE = "boundary=";

  private static final String BROTLY_ACCEPT_ENCODING_SUFFIX = ", br";

  private HttpUtils() {
  }

  /**
   * Computes the Host header value from a URI.
   * <p>
   * If the port is the default port for the scheme or is not specified, only the host is returned.
   * Otherwise, the host and port are returned in the format "host:port".
   * </p>
   *
   * @param uri the URI
   * @return the Host header value
   */
  public static String hostHeader(Uri uri) {
    String host = uri.getHost();
    int port = uri.getPort();
    return port == -1 || port == uri.getSchemeDefaultPort() ? host : host + ":" + port;
  }

  /**
   * Computes the Origin header value from a URI.
   * <p>
   * The Origin header includes the scheme, host, and port (if non-default).
   * For secure URIs, "https://" is used; otherwise, "http://".
   * </p>
   *
   * @param uri the URI
   * @return the Origin header value
   */
  public static String originHeader(Uri uri) {
    StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
    sb.append(uri.isSecured() ? "https://" : "http://").append(uri.getHost());
    if (uri.getExplicitPort() != uri.getSchemeDefaultPort()) {
      sb.append(':').append(uri.getPort());
    }
    return sb.toString();
  }

  /**
   * Extracts the charset attribute from a Content-Type header.
   * <p>
   * Parses the Content-Type header and returns the Charset specified in the charset attribute.
   * </p>
   *
   * @param contentType the Content-Type header value
   * @return the Charset, or null if not found or invalid
   */
  public static Charset extractContentTypeCharsetAttribute(String contentType) {
    String charsetName = extractContentTypeAttribute(contentType, CONTENT_TYPE_CHARSET_ATTRIBUTE);
    return charsetName != null ? Charset.forName(charsetName) : null;
  }

  /**
   * Extracts the boundary attribute from a Content-Type header.
   * <p>
   * Parses the Content-Type header and returns the boundary value used for multipart content.
   * </p>
   *
   * @param contentType the Content-Type header value
   * @return the boundary value, or null if not found
   */
  public static String extractContentTypeBoundaryAttribute(String contentType) {
    return extractContentTypeAttribute(contentType, CONTENT_TYPE_BOUNDARY_ATTRIBUTE);
  }

  /**
   * Extracts a specific attribute value from a Content-Type header.
   * <p>
   * Searches for the specified attribute name in the Content-Type header and returns
   * its value, trimming any surrounding quotes or whitespace.
   * </p>
   *
   * @param contentType the Content-Type header value
   * @param attribute   the attribute name to extract (e.g., "charset=", "boundary=")
   * @return the attribute value, or null if not found
   */
  private static String extractContentTypeAttribute(String contentType, String attribute) {
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
  private static byte[] MULTIPART_CHARS = "-_1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(US_ASCII);

  /**
   * Computes a random multipart boundary.
   * <p>
   * Generates a random boundary string of 30-40 characters using alphanumeric characters,
   * hyphens, and underscores. This boundary is used to delimit parts in multipart/form-data requests.
   * </p>
   *
   * @return a byte array containing the random boundary
   */
  public static byte[] computeMultipartBoundary() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    byte[] bytes = new byte[random.nextInt(11) + 30];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = MULTIPART_CHARS[random.nextInt(MULTIPART_CHARS.length)];
    }
    return bytes;
  }

  /**
   * Adds a boundary attribute to a Content-Type header value.
   * <p>
   * Appends the boundary attribute to the given Content-Type base value, properly
   * handling semicolon separators.
   * </p>
   *
   * @param base     the base Content-Type value
   * @param boundary the boundary value to add
   * @return the complete Content-Type header value with boundary
   */
  public static String patchContentTypeWithBoundaryAttribute(CharSequence base, byte[] boundary) {
    StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder().append(base);
    if (base.length() != 0 && base.charAt(base.length() - 1) != ';') {
      sb.append(';');
    }
    return sb.append(' ').append(CONTENT_TYPE_BOUNDARY_ATTRIBUTE).append(new String(boundary, US_ASCII)).toString();
  }

  /**
   * Determines whether to follow redirects for a request.
   * <p>
   * Returns the request-specific setting if present, otherwise falls back to the client configuration.
   * </p>
   *
   * @param config  the client configuration
   * @param request the request
   * @return true if redirects should be followed, false otherwise
   */
  public static boolean followRedirect(AsyncHttpClientConfig config, Request request) {
    return request.getFollowRedirect() != null ? request.getFollowRedirect() : config.isFollowRedirect();
  }

  /**
   * URL-encodes form parameters into a ByteBuffer.
   * <p>
   * Encodes the list of parameters in application/x-www-form-urlencoded format.
   * </p>
   *
   * @param params  the list of parameters to encode
   * @param charset the character set to use for encoding
   * @return a ByteBuffer containing the encoded parameters
   */
  public static ByteBuffer urlEncodeFormParams(List<Param> params, Charset charset) {
    return StringUtils.charSequence2ByteBuffer(urlEncodeFormParams0(params, charset), US_ASCII);
  }

  /**
   * URL-encodes form parameters into a StringBuilder.
   *
   * @param params  the list of parameters to encode
   * @param charset the character set to use for encoding
   * @return a StringBuilder containing the encoded parameters
   */
  private static StringBuilder urlEncodeFormParams0(List<Param> params, Charset charset) {
    StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
    for (Param param : params) {
      encodeAndAppendFormParam(sb, param.getName(), param.getValue(), charset);
    }
    sb.setLength(sb.length() - 1);
    return sb;
  }

  /**
   * Encodes and appends a single form parameter.
   *
   * @param sb      the StringBuilder to append to
   * @param name    the parameter name
   * @param value   the parameter value (may be null)
   * @param charset the character set to use for encoding
   */
  private static void encodeAndAppendFormParam(StringBuilder sb, String name, String value, Charset charset) {
    encodeAndAppendFormField(sb, name, charset);
    if (value != null) {
      sb.append('=');
      encodeAndAppendFormField(sb, value, charset);
    }
    sb.append('&');
  }

  /**
   * Encodes and appends a single form field (name or value).
   *
   * @param sb      the StringBuilder to append to
   * @param field   the field to encode
   * @param charset the character set to use for encoding
   */
  private static void encodeAndAppendFormField(StringBuilder sb, String field, Charset charset) {
    if (charset.equals(UTF_8)) {
      Utf8UrlEncoder.encodeAndAppendFormElement(sb, field);
    } else {
      try {
        // TODO there's probably room for perf improvements
        sb.append(URLEncoder.encode(field, charset.name()));
      } catch (UnsupportedEncodingException e) {
        // can't happen, as Charset was already resolved
      }
    }
  }

  /**
   * Filters out Brotli encoding from an Accept-Encoding header value.
   * <p>
   * Removes the ", br" suffix if present, as Brotli compression is not currently supported.
   * </p>
   *
   * @param acceptEncoding the Accept-Encoding header value
   * @return the filtered Accept-Encoding value
   */
  public static CharSequence filterOutBrotliFromAcceptEncoding(String acceptEncoding) {
    // we don't support Brotly ATM
    if (acceptEncoding.endsWith(BROTLY_ACCEPT_ENCODING_SUFFIX)) {
      return acceptEncoding.subSequence(0, acceptEncoding.length() - BROTLY_ACCEPT_ENCODING_SUFFIX.length());
    }
    return acceptEncoding;
  }
}
