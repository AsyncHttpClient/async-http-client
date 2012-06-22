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
package com.ning.http.util;

import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;

import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;

public final class AuthenticatorUtils {

    public static String computeBasicAuthentication(Realm realm) throws UnsupportedEncodingException {
        String s = realm.getPrincipal() + ":" + realm.getPassword();
        return "Basic " + Base64.encode(s.getBytes(realm.getEncoding()));
    }

    public static String computeBasicAuthentication(ProxyServer proxyServer) throws UnsupportedEncodingException {
        String s = proxyServer.getPrincipal() + ":" + proxyServer.getPassword();
        return "Basic " + Base64.encode(s.getBytes(proxyServer.getEncoding()));
    }

    public static String computeDigestAuthentication(Realm realm) throws NoSuchAlgorithmException, UnsupportedEncodingException {

        StringBuilder builder = new StringBuilder().append("Digest ");
        construct(builder, "username", realm.getPrincipal());
        construct(builder, "realm", realm.getRealmName());
        construct(builder, "nonce", realm.getNonce());
        construct(builder, "uri", realm.getUri());
        builder.append("algorithm").append('=').append(realm.getAlgorithm()).append(", ");

        construct(builder, "response", realm.getResponse());
        if (realm.getOpaque() != null && realm.getOpaque() != null && realm.getOpaque().equals("") == false)
            construct(builder, "opaque", realm.getOpaque());
        builder.append("qop").append('=').append(realm.getQop()).append(", ");
        builder.append("nc").append('=').append(realm.getNc()).append(", ");
        construct(builder, "cnonce", realm.getCnonce(), true);

        return new String(builder.toString().getBytes("ISO_8859_1"));
    }

    private static StringBuilder construct(StringBuilder builder, String name, String value) {
        return construct(builder, name, value, false);
    }

    private static StringBuilder construct(StringBuilder builder, String name, String value, boolean tail) {
        return builder.append(name).append('=').append('"').append(value).append(tail ? "\"" : "\", ");
    }
}
