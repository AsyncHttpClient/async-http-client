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

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

public final class AuthenticatorUtils {

    private final static Charset UTF_8 = Charset.forName("UTF-8");
    private final static Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

    private static final String NC = "00000001";
    private Realm securityRealm;
    private Map details;

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
        construct(builder, "algorithm", realm.getAlgorithm());
        construct(builder, "response", digest(realm));
        construct(builder, "qop", realm.getQop());
        construct(builder, "nc", realm.getNc());
        construct(builder, "cnonce", realm.getCnonce());

        return builder.toString();
    }

    private static StringBuilder construct(StringBuilder builder, String name, String value) {
        return builder.append(name).append('=').append('"').append(value).append("\", ");
    }

    protected static String digest(Realm realm) throws NoSuchAlgorithmException {
            String cnonce = newCnonce(realm);
            MessageDigest md = MessageDigest.getInstance("MD5");

            md.update(realm.getPrincipal().getBytes(ISO_8859_1));
            md.update((byte) ':');
            md.update(realm.getRealmName().getBytes(ISO_8859_1));
            md.update((byte) ':');
            md.update(realm.getPassword().getBytes(ISO_8859_1));
            byte[] ha1 = md.digest();
            md.reset();
            md.update(realm.getMethodName().getBytes(ISO_8859_1));
            md.update((byte) ':');
            md.update(realm.getUri().getBytes(ISO_8859_1));
            byte[] ha2 = md.digest();

            md.update(convert(ha1, 16).getBytes(ISO_8859_1));
            md.update((byte) ':');
            md.update(realm.getNonce().getBytes(ISO_8859_1));
            md.update((byte) ':');
            md.update(NC.getBytes(ISO_8859_1));
            md.update((byte) ':');
            md.update(cnonce.getBytes(ISO_8859_1));
            md.update((byte) ':');
            md.update(realm.getMethodName().getBytes(ISO_8859_1));
            md.update((byte) ':');
            md.update(convert(ha2, 16).getBytes(ISO_8859_1));
            byte[] digest = md.digest();

            return encode(digest);
    }

    private static String newCnonce(Realm realm) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest(String.valueOf(System.currentTimeMillis()).getBytes(ISO_8859_1));
            return encode(b);
        }
        catch (Exception e) {
            return null;
        }
    }

    private static String encode(byte[] data) {
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            buffer.append(Integer.toHexString((data[i] & 0xf0) >>> 4));
            buffer.append(Integer.toHexString(data[i] & 0x0f));
        }
        return buffer.toString();
    }

    private static String convert(byte[] bytes, int base)
    {
        StringBuilder buf = new StringBuilder();
        for (byte b : bytes)
        {
            int bi=0xff&b;
            int c='0'+(bi/base)%base;
            if (c>'9')
                c= 'a'+(c-'0'-10);
            buf.append((char)c);
            c='0'+bi%base;
            if (c>'9')
                c= 'a'+(c-'0'-10);
            buf.append((char)c);
        }
        return buf.toString();
    }

}
