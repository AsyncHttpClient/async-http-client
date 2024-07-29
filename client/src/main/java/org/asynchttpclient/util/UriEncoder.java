/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.util;

import org.asynchttpclient.Param;
import org.asynchttpclient.uri.Uri;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.asynchttpclient.util.Utf8UrlEncoder.encodeAndAppendQuery;

public enum UriEncoder {

    FIXING {
        @Override
        public String encodePath(String path) {
            return Utf8UrlEncoder.encodePath(path);
        }

        private void encodeAndAppendQueryParam(final StringBuilder sb, final CharSequence name, final @Nullable CharSequence value) {
            Utf8UrlEncoder.encodeAndAppendQueryElement(sb, name);
            if (value != null) {
                sb.append('=');
                Utf8UrlEncoder.encodeAndAppendQueryElement(sb, value);
            }
            sb.append('&');
        }

        private void encodeAndAppendQueryParams(final StringBuilder sb, final List<Param> queryParams) {
            for (Param param : queryParams) {
                encodeAndAppendQueryParam(sb, param.getName(), param.getValue());
            }
        }

        @Override
        protected String withQueryWithParams(final String query, final List<Param> queryParams) {
            // concatenate encoded query + encoded query params
            StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
            encodeAndAppendQuery(sb, query);
            sb.append('&');
            encodeAndAppendQueryParams(sb, queryParams);
            sb.setLength(sb.length() - 1);
            return sb.toString();
        }

        @Override
        protected String withQueryWithoutParams(final String query) {
            // encode query
            StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
            encodeAndAppendQuery(sb, query);
            return sb.toString();
        }

        @Override
        protected String withoutQueryWithParams(final List<Param> queryParams) {
            // concatenate encoded query params
            StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
            encodeAndAppendQueryParams(sb, queryParams);
            sb.setLength(sb.length() - 1);
            return sb.toString();
        }
    },

    RAW {
        @Override
        public String encodePath(String path) {
            return path;
        }

        private void appendRawQueryParam(StringBuilder sb, String name, @Nullable String value) {
            sb.append(name);
            if (value != null) {
                sb.append('=').append(value);
            }
            sb.append('&');
        }

        private void appendRawQueryParams(final StringBuilder sb, final List<Param> queryParams) {
            for (Param param : queryParams) {
                appendRawQueryParam(sb, param.getName(), param.getValue());
            }
        }

        @Override
        protected String withQueryWithParams(final String query, final List<Param> queryParams) {
            // concatenate raw query + raw query params
            StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
            sb.append(query);
            appendRawQueryParams(sb, queryParams);
            sb.setLength(sb.length() - 1);
            return sb.toString();
        }

        @Override
        protected String withQueryWithoutParams(final String query) {
            // return raw query as is
            return query;
        }

        @Override
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

    protected abstract String withQueryWithParams(String query, List<Param> queryParams);

    protected abstract String withQueryWithoutParams(String query);

    protected abstract String withoutQueryWithParams(List<Param> queryParams);

    private String withQuery(final String query, final @Nullable List<Param> queryParams) {
        return isNonEmpty(queryParams) ? withQueryWithParams(query, queryParams) : withQueryWithoutParams(query);
    }

    private @Nullable String withoutQuery(final @Nullable List<Param> queryParams) {
        return isNonEmpty(queryParams) ? withoutQueryWithParams(queryParams) : null;
    }

    public Uri encode(Uri uri, @Nullable List<Param> queryParams) {
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

    private @Nullable String encodeQuery(final @Nullable String query, final @Nullable List<Param> queryParams) {
        return isNonEmpty(query) ? withQuery(query, queryParams) : withoutQuery(queryParams);
    }
}
