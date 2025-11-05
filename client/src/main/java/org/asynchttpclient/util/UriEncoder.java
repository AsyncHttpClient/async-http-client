/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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

import org.asynchttpclient.Param;
import org.asynchttpclient.uri.Uri;

import java.util.List;

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.asynchttpclient.util.Utf8UrlEncoder.encodeAndAppendQuery;

/**
 * Enumeration of URI encoding strategies for HTTP requests.
 * <p>
 * This enum provides two strategies for encoding URIs:
 * </p>
 * <ul>
 *   <li>{@link #FIXING} - Properly encodes paths and query parameters according to RFC 3986</li>
 *   <li>{@link #RAW} - Leaves URIs unencoded, passing them through as-is</li>
 * </ul>
 * <p>
 * The appropriate encoder can be selected using {@link #uriEncoder(boolean)}.
 * </p>
 */
public enum UriEncoder {

  /**
   * Encoder that properly encodes paths and query parameters according to RFC 3986.
   * <p>
   * This encoder ensures that URI components are properly percent-encoded, making them
   * safe for transmission in HTTP requests.
   * </p>
   */
  FIXING {
    /**
     * Encodes a URI path according to RFC 3986.
     *
     * @param path the path to encode
     * @return the encoded path
     */
    public String encodePath(String path) {
      return Utf8UrlEncoder.encodePath(path);
    }

    private void encodeAndAppendQueryParam(final StringBuilder sb, final CharSequence name, final CharSequence value) {
      Utf8UrlEncoder.encodeAndAppendQueryElement(sb, name);
      if (value != null) {
        sb.append('=');
        Utf8UrlEncoder.encodeAndAppendQueryElement(sb, value);
      }
      sb.append('&');
    }

    private void encodeAndAppendQueryParams(final StringBuilder sb, final List<Param> queryParams) {
      for (Param param : queryParams)
        encodeAndAppendQueryParam(sb, param.getName(), param.getValue());
    }

    protected String withQueryWithParams(final String query, final List<Param> queryParams) {
      // concatenate encoded query + encoded query params
      StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
      encodeAndAppendQuery(sb, query);
      sb.append('&');
      encodeAndAppendQueryParams(sb, queryParams);
      sb.setLength(sb.length() - 1);
      return sb.toString();
    }

    protected String withQueryWithoutParams(final String query) {
      // encode query
      StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
      encodeAndAppendQuery(sb, query);
      return sb.toString();
    }

    protected String withoutQueryWithParams(final List<Param> queryParams) {
      // concatenate encoded query params
      StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
      encodeAndAppendQueryParams(sb, queryParams);
      sb.setLength(sb.length() - 1);
      return sb.toString();
    }
  },

  /**
   * Encoder that leaves URI components unencoded.
   * <p>
   * This encoder passes paths and query parameters through without any encoding.
   * Use with caution, as it assumes the URI is already properly encoded or does not
   * contain characters that require encoding.
   * </p>
   */
  RAW {
    /**
     * Returns the path without encoding.
     *
     * @param path the path
     * @return the unmodified path
     */
    public String encodePath(String path) {
      return path;
    }

    private void appendRawQueryParam(StringBuilder sb, String name, String value) {
      sb.append(name);
      if (value != null)
        sb.append('=').append(value);
      sb.append('&');
    }

    private void appendRawQueryParams(final StringBuilder sb, final List<Param> queryParams) {
      for (Param param : queryParams)
        appendRawQueryParam(sb, param.getName(), param.getValue());
    }

    protected String withQueryWithParams(final String query, final List<Param> queryParams) {
      // concatenate raw query + raw query params
      StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
      sb.append(query);
      appendRawQueryParams(sb, queryParams);
      sb.setLength(sb.length() - 1);
      return sb.toString();
    }

    protected String withQueryWithoutParams(final String query) {
      // return raw query as is
      return query;
    }

    protected String withoutQueryWithParams(final List<Param> queryParams) {
      // concatenate raw queryParams
      StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
      appendRawQueryParams(sb, queryParams);
      sb.setLength(sb.length() - 1);
      return sb.toString();
    }
  };

  /**
   * Returns the appropriate URI encoder based on the encoding preference.
   *
   * @param disableUrlEncoding true to disable URL encoding (returns {@link #RAW}),
   *                          false to enable encoding (returns {@link #FIXING})
   * @return the appropriate URI encoder
   */
  public static UriEncoder uriEncoder(boolean disableUrlEncoding) {
    return disableUrlEncoding ? RAW : FIXING;
  }

  /**
   * Encodes a query string with additional query parameters.
   *
   * @param query       the existing query string
   * @param queryParams additional query parameters to append
   * @return the complete encoded query string
   */
  protected abstract String withQueryWithParams(final String query, final List<Param> queryParams);

  /**
   * Encodes a query string without additional parameters.
   *
   * @param query the query string to encode
   * @return the encoded query string
   */
  protected abstract String withQueryWithoutParams(final String query);

  /**
   * Encodes query parameters when there is no existing query string.
   *
   * @param queryParams the query parameters to encode
   * @return the encoded query string
   */
  protected abstract String withoutQueryWithParams(final List<Param> queryParams);

  /**
   * Encodes a query string, optionally merging with additional query parameters.
   *
   * @param query       the existing query string
   * @param queryParams additional query parameters
   * @return the complete encoded query string
   */
  private String withQuery(final String query, final List<Param> queryParams) {
    return isNonEmpty(queryParams) ? withQueryWithParams(query, queryParams) : withQueryWithoutParams(query);
  }

  /**
   * Encodes query parameters when there is no existing query string.
   *
   * @param queryParams the query parameters
   * @return the encoded query string, or null if no parameters
   */
  private String withoutQuery(final List<Param> queryParams) {
    return isNonEmpty(queryParams) ? withoutQueryWithParams(queryParams) : null;
  }

  /**
   * Encodes a URI with optional additional query parameters.
   * <p>
   * This method encodes the path and merges any additional query parameters with
   * the existing query string, if present.
   * </p>
   *
   * @param uri         the URI to encode
   * @param queryParams additional query parameters to merge
   * @return a new URI with encoded components
   */
  public Uri encode(Uri uri, List<Param> queryParams) {
    String newPath = encodePath(uri.getPath());
    String newQuery = encodeQuery(uri.getQuery(), queryParams);
    return new Uri(uri.getScheme(),
            uri.getUserInfo(),
            uri.getHost(),
            uri.getPort(),
            newPath,
            newQuery,
            uri.getFragment());
  }

  /**
   * Encodes a URI path.
   *
   * @param path the path to encode
   * @return the encoded path
   */
  protected abstract String encodePath(String path);

  /**
   * Encodes a query string with optional additional parameters.
   *
   * @param query       the existing query string
   * @param queryParams additional query parameters
   * @return the encoded query string
   */
  private String encodeQuery(final String query, final List<Param> queryParams) {
    return isNonEmpty(query) ? withQuery(query, queryParams) : withoutQuery(queryParams);
  }
}
