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

import static org.asynchttpclient.util.AsyncHttpProviderUtils.getNonEmptyPath;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.uri.Uri;

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
            Uri uri = realm.getUri();
            if (realm.isUseAbsoluteURI()) {
                return realm.isOmitQuery() && MiscUtils.isNonEmpty(uri.getQuery()) ? uri.withNewQuery(null).toUrl() : uri.toUrl();
            } else {
                String path = getNonEmptyPath(uri);
                return realm.isOmitQuery() || !MiscUtils.isNonEmpty(uri.getQuery()) ? path : path + "?" + uri.getQuery();
            }
        }
    }

    public static String computeDigestAuthentication(Realm realm) throws NoSuchAlgorithmException {

        StringBuilder builder = new StringBuilder().append("Digest ");
        append(builder, "username", realm.getPrincipal(), true);
        append(builder, "realm", realm.getRealmName(), true);
        append(builder, "nonce", realm.getNonce(), true);
        append(builder, "uri", computeRealmURI(realm), true);
        append(builder, "algorithm", realm.getAlgorithm(), false);

        append(builder, "response", realm.getResponse(), true);
        if (isNonEmpty(realm.getOpaque()))
            append(builder, "opaque", realm.getOpaque(), true);
        append(builder, "qop", realm.getQop(), false);
        append(builder, "nc", realm.getNc(), false);
        append(builder, "cnonce", realm.getCnonce(), true);
        builder.setLength(builder.length() - 2); // remove tailing ", "

        return new String(builder.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static StringBuilder append(StringBuilder builder, String name, String value, boolean quoted) {
        builder.append(name).append('=');
        if (quoted)
            builder.append('"').append(value).append('"');
        else
            builder.append(value);

        return builder.append(", ");
    }
}
