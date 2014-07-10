/*
 * Copyright (c) 2010-2013 Sonatype, Inc. All rights reserved.
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

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.uri.UriComponents;

import java.security.NoSuchAlgorithmException;

public final class AuthenticatorUtils {

    public static String computeBasicAuthentication(Realm realm) {
        String s = realm.getPrincipal() + ":" + realm.getPassword();
        return "Basic " + Base64.encode(s.getBytes(realm.getCharset()));
    }

    public static String computeBasicAuthentication(ProxyServer proxyServer) {
        String s = proxyServer.getPrincipal() + ":" + proxyServer.getPassword();
        return "Basic " + Base64.encode(s.getBytes(proxyServer.getCharset()));
    }

    private static String computeRealmURI(Realm realm) {
        if (realm.isTargetProxy()) {
            return "/";
        } else {
            UriComponents uri = realm.getUri();
            boolean omitQuery = realm.isOmitQuery() && MiscUtils.isNonEmpty(uri.getQuery());
            if (realm.isUseAbsoluteURI()) {
                return omitQuery ? uri.withNewQuery(null).toUrl() : uri.toUrl();
            } else {
                String path = uri.getPath();
                return omitQuery ? path : path + "?" + uri.getQuery();
            }
        }
    }

    public static String computeDigestAuthentication(Realm realm) throws NoSuchAlgorithmException {

        StringBuilder builder = new StringBuilder().append("Digest ");
        construct(builder, "username", realm.getPrincipal());
        construct(builder, "realm", realm.getRealmName());
        construct(builder, "nonce", realm.getNonce());
        construct(builder, "uri", computeRealmURI(realm));
        builder.append("algorithm").append('=').append(realm.getAlgorithm()).append(", ");

        construct(builder, "response", realm.getResponse());
        if (isNonEmpty(realm.getOpaque()))
            construct(builder, "opaque", realm.getOpaque());
        builder.append("qop").append('=').append(realm.getQop()).append(", ");
        builder.append("nc").append('=').append(realm.getNc()).append(", ");
        construct(builder, "cnonce", realm.getCnonce(), true);

        return new String(builder.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    public static String computeDigestAuthentication(ProxyServer proxy) {
        try {
            StringBuilder builder = new StringBuilder().append("Digest ");
            construct(builder, "username", proxy.getPrincipal(), true);
            return new String(builder.toString().getBytes(StandardCharsets.ISO_8859_1));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static StringBuilder construct(StringBuilder builder, String name, String value) {
        return construct(builder, name, value, false);
    }

    private static StringBuilder construct(StringBuilder builder, String name, String value, boolean tail) {
        return builder.append(name).append('=').append('"').append(value).append(tail ? "\"" : "\", ");
    }
}
