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

public enum UriEncoder {

  FIXING {
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

  RAW {
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

  public static UriEncoder uriEncoder(boolean disableUrlEncoding) {
    return disableUrlEncoding ? RAW : FIXING;
  }

  protected abstract String withQueryWithParams(final String query, final List<Param> queryParams);

  protected abstract String withQueryWithoutParams(final String query);

  protected abstract String withoutQueryWithParams(final List<Param> queryParams);

  private String withQuery(final String query, final List<Param> queryParams) {
    return isNonEmpty(queryParams) ? withQueryWithParams(query, queryParams) : withQueryWithoutParams(query);
  }

  private String withoutQuery(final List<Param> queryParams) {
    return isNonEmpty(queryParams) ? withoutQueryWithParams(queryParams) : null;
  }

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

  protected abstract String encodePath(String path);

  private String encodeQuery(final String query, final List<Param> queryParams) {
    return isNonEmpty(query) ? withQuery(query, queryParams) : withoutQuery(queryParams);
  }
}
