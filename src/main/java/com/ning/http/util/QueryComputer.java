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
package com.ning.http.util;

import static com.ning.http.util.MiscUtil.isNonEmpty;

import com.ning.http.client.Param;

import java.util.List;

public enum QueryComputer {

    URL_ENCODING_ENABLED_QUERY_COMPUTER {

        protected String withQueryWithParams(final String query, final List<Param> queryParams) {
            // concatenate encoded query + encoded query params
            StringBuilder sb = new StringBuilder(query.length() + 16);
            encodeAndAppendQuery(sb, query);
            encodeAndAppendQueryParams(sb, queryParams);
            sb.setLength(sb.length() - 1);
            return sb.toString();
        }

        protected String withQueryWithoutParams(final String query) {
            // encode query
            StringBuilder sb = new StringBuilder(query.length() + 16);
            encodeAndAppendQuery(sb, query);
            sb.setLength(sb.length() - 1);
            return sb.toString();
        }

        protected String withoutQueryWithParams(final List<Param> queryParams) {
            // concatenate encoded query params
            StringBuilder sb = new StringBuilder(queryParams.size() * 16);
            encodeAndAppendQueryParams(sb, queryParams);
            sb.setLength(sb.length() - 1);
            return sb.toString();
        }
    }, //

    URL_ENCODING_DISABLED_QUERY_COMPUTER {

        protected String withQueryWithParams(final String query, final List<Param> queryParams) {
            // concatenate raw query + raw query params
            StringBuilder sb = new StringBuilder(query);
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
            StringBuilder sb = new StringBuilder(queryParams.size() * 16);
            appendRawQueryParams(sb, queryParams);
            sb.setLength(sb.length() - 1);
            return sb.toString();
        }
    };

    public static QueryComputer queryComputer(boolean disableUrlEncoding) {
        return disableUrlEncoding ? URL_ENCODING_DISABLED_QUERY_COMPUTER : URL_ENCODING_ENABLED_QUERY_COMPUTER;
    }

    protected final void appendRawQueryParams(final StringBuilder sb, final List<Param> queryParams) {
        for (Param param : queryParams)
            appendRawQueryParam(sb, param.getName(), param.getValue());
    }

    private final void appendRawQueryParam(StringBuilder sb, String name, String value) {
        sb.append(name);
        if (value != null)
            sb.append('=').append(value);
        sb.append('&');
    }

    private void encodeAndAppendQueryParam(final StringBuilder sb, final String name, final String value) {
        UTF8UrlEncoder.appendEncoded(sb, name);
        if (value != null) {
            sb.append('=');
            UTF8UrlEncoder.appendEncoded(sb, value);
        }
        sb.append('&');
    }

    // FIXME it's probably possible to have only one pass instead of decoding then re-encoding
    private final void encodeAndAppendQuery(final StringBuilder sb, final String query, final boolean decode) {
        int pos;
        for (String queryParamString : query.split("&")) {
            pos = queryParamString.indexOf('=');
            if (pos <= 0) {
                String decodedName = decode ? UTF8UrlDecoder.decode(queryParamString) : queryParamString;
                encodeAndAppendQueryParam(sb, decodedName, null);
            } else {
                String name = queryParamString.substring(0, pos);
                String decodedName = decode ? UTF8UrlDecoder.decode(name) : name;
                String value = queryParamString.substring(pos + 1);
                String decodedValue = decode ? UTF8UrlDecoder.decode(value) : value;
                encodeAndAppendQueryParam(sb, decodedName, decodedValue);
            }
        }
    }

    private final boolean decodeRequired(final String query) {
        return query.indexOf('%') != -1 || query.indexOf('+') != -1;
    }

    protected final void encodeAndAppendQuery(final StringBuilder sb, final String query) {
        encodeAndAppendQuery(sb, query, decodeRequired(query));
    }

    protected final void encodeAndAppendQueryParams(final StringBuilder sb, final List<Param> queryParams) {
        for (Param param : queryParams)
            encodeAndAppendQueryParam(sb, param.getName(), param.getValue());
    }

    protected abstract String withQueryWithParams(final String query, final List<Param> queryParams);

    protected abstract String withQueryWithoutParams(final String query);

    protected abstract String withoutQueryWithParams(final List<Param> queryParams);

    private final String withQuery(final String query, final List<Param> queryParams) {
        return isNonEmpty(queryParams) ? withQueryWithParams(query, queryParams) : withQueryWithoutParams(query);
    }

    private final String withoutQuery(final List<Param> queryParams) {
        return isNonEmpty(queryParams) ? withoutQueryWithParams(queryParams) : null;
    }

    public final String computeFullQueryString(final String query, final List<Param> queryParams) {
        return isNonEmpty(query) ? withQuery(query, queryParams) : withoutQuery(queryParams);
    }
}
