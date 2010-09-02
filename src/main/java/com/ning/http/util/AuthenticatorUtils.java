/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.util;

import com.ning.http.client.Realm;

import java.security.NoSuchAlgorithmException;

public final class AuthenticatorUtils {

    public static String computeBasicAuthentication(Realm realm) {
        String s = realm.getPrincipal() + ":" + realm.getPassword();
        return "Basic " + Base64.encode(s.getBytes());
    }

    public static String computeDigestAuthentication(Realm realm) throws NoSuchAlgorithmException {

        StringBuilder builder = new StringBuilder().append("Digest ");
        construct(builder, "username", realm.getPrincipal());
        construct(builder, "realm", realm.getRealmName());
        construct(builder, "nonce", realm.getNonce());
        construct(builder, "uri", realm.getUri());
        builder.append("algorithm").append('=').append(realm.getAlgorithm()).append(", ");

        construct(builder, "response", realm.getResponse());
        builder.append("qop").append('=').append(realm.getQop()).append(", ");
        builder.append("nc").append('=').append(realm.getNc()).append(", ");
        construct(builder, "cnonce", realm.getCnonce(), true);

        return builder.toString();
    }

    private static StringBuilder construct(StringBuilder builder, String name, String value) {
        return construct(builder,name,value,false);
    }

    private static StringBuilder construct(StringBuilder builder, String name, String value, boolean tail) {
        return builder.append(name).append('=').append('"').append(value).append(tail ? "\"" : "\", ");
    }
}
