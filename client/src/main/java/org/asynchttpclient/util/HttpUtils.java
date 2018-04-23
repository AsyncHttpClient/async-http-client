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
 * {@link org.asynchttpclient.AsyncHttpClient} common utilities.
 */
public class HttpUtils {

  public static final AsciiString ACCEPT_ALL_HEADER_VALUE = new AsciiString("*/*");

  public static final AsciiString GZIP_DEFLATE = new AsciiString(HttpHeaderValues.GZIP + "," + HttpHeaderValues.DEFLATE);

  private static final String CONTENT_TYPE_CHARSET_ATTRIBUTE = "charset=";

  private static final String CONTENT_TYPE_BOUNDARY_ATTRIBUTE = "boundary=";

  private static final String BROTLY_ACCEPT_ENCODING_SUFFIX = ", br";

  private HttpUtils() {
  }

  public static String hostHeader(Uri uri) {
    String host = uri.getHost();
    int port = uri.getPort();
    return port == -1 || port == uri.getSchemeDefaultPort() ? host : host + ":" + port;
  }

  public static String originHeader(Uri uri) {
    StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
    sb.append(uri.isSecured() ? "https://" : "http://").append(uri.getHost());
    if (uri.getExplicitPort() != uri.getSchemeDefaultPort()) {
      sb.append(':').append(uri.getPort());
    }
    return sb.toString();
  }

  public static Charset extractContentTypeCharsetAttribute(String contentType) {
    String charsetName = extractContentTypeAttribute(contentType, CONTENT_TYPE_CHARSET_ATTRIBUTE);
    return charsetName != null ? Charset.forName(charsetName) : null;
  }

  public static String extractContentTypeBoundaryAttribute(String contentType) {
    return extractContentTypeAttribute(contentType, CONTENT_TYPE_BOUNDARY_ATTRIBUTE);
  }

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

  // a random size from 30 to 40
  public static byte[] computeMultipartBoundary() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    byte[] bytes = new byte[random.nextInt(11) + 30];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = MULTIPART_CHARS[random.nextInt(MULTIPART_CHARS.length)];
    }
    return bytes;
  }

  public static String patchContentTypeWithBoundaryAttribute(CharSequence base, byte[] boundary) {
    StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder().append(base);
    if (base.length() != 0 && base.charAt(base.length() - 1) != ';') {
      sb.append(';');
    }
    return sb.append(' ').append(CONTENT_TYPE_BOUNDARY_ATTRIBUTE).append(new String(boundary, US_ASCII)).toString();
  }

  public static boolean followRedirect(AsyncHttpClientConfig config, Request request) {
    return request.getFollowRedirect() != null ? request.getFollowRedirect() : config.isFollowRedirect();
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

  private static void encodeAndAppendFormParam(StringBuilder sb, String name, String value, Charset charset) {
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
      try {
        // TODO there's probably room for perf improvements
        sb.append(URLEncoder.encode(field, charset.name()));
      } catch (UnsupportedEncodingException e) {
        // can't happen, as Charset was already resolved
      }
    }
  }

  public static CharSequence filterOutBrotliFromAcceptEncoding(String acceptEncoding) {
    // we don't support Brotly ATM
    if (acceptEncoding.endsWith(BROTLY_ACCEPT_ENCODING_SUFFIX)) {
      return acceptEncoding.subSequence(0, acceptEncoding.length() - BROTLY_ACCEPT_ENCODING_SUFFIX.length());
    }
    return acceptEncoding;
  }
}
