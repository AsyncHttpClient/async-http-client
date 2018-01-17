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

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Param;
import org.asynchttpclient.Request;
import org.asynchttpclient.uri.Uri;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;

import static java.nio.charset.StandardCharsets.*;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

/**
 * {@link org.asynchttpclient.AsyncHttpClient} common utilities.
 */
public class HttpUtils {

  private static final String CONTENT_TYPE_CHARSET_ATTRIBUTE = "charset=";

  public static void validateSupportedScheme(Uri uri) {
    final String scheme = uri.getScheme();
    if (scheme == null || !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")
            && !scheme.equalsIgnoreCase("ws") && !scheme.equalsIgnoreCase("wss")) {
      throw new IllegalArgumentException("The URI scheme, of the URI " + uri
              + ", must be equal (ignoring case) to 'http', 'https', 'ws', or 'wss'");
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
    return uri1.getScheme().equals(uri2.getScheme()) && uri1.getHost().equals(uri2.getHost())
            && uri1.getExplicitPort() == uri2.getExplicitPort();
  }

  /**
   * @param uri the uri
   * @return the raw path or "/" if it's null
   */
  public static String getNonEmptyPath(Uri uri) {
    return isNonEmpty(uri.getPath()) ? uri.getPath() : "/";
  }

  public static Charset extractCharset(String contentType) {
    if (contentType != null) {
      for (int i = 0; i < contentType.length(); i++) {
        if (contentType.regionMatches(true, i, CONTENT_TYPE_CHARSET_ATTRIBUTE, 0,
                CONTENT_TYPE_CHARSET_ATTRIBUTE.length())) {
          int start = i + CONTENT_TYPE_CHARSET_ATTRIBUTE.length();

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

          String charsetName = contentType.substring(start, end);
          return Charset.forName(charsetName);
        }
      }
    }

    return null;
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

  public static String computeOriginHeader(Uri uri) {
    StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
    sb.append(uri.isSecured() ? "https://" : "http://").append(uri.getHost());
    if (uri.getExplicitPort() != uri.getSchemeDefaultPort()) {
      sb.append(':').append(uri.getPort());
    }
    return sb.toString();
  }
}
