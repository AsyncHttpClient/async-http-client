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
package org.asynchttpclient;

import static java.nio.charset.StandardCharsets.UTF_16;
import static org.asynchttpclient.Dsl.*;
import static org.testng.Assert.assertEquals;

import java.math.BigInteger;
import java.security.MessageDigest;

import org.asynchttpclient.uri.Uri;
import org.testng.annotations.Test;

public class RealmTest {
    @Test(groups = "standalone")
    public void testClone() {
        Realm orig = basicAuthRealm("user", "pass").setCharset(UTF_16)//
                .setUsePreemptiveAuth(true)//
                .setRealmName("realm")//
                .setAlgorithm("algo").build();

        Realm clone = realm(orig).build();
        assertEquals(clone.getPrincipal(), orig.getPrincipal());
        assertEquals(clone.getPassword(), orig.getPassword());
        assertEquals(clone.getCharset(), orig.getCharset());
        assertEquals(clone.isUsePreemptiveAuth(), orig.isUsePreemptiveAuth());
        assertEquals(clone.getRealmName(), orig.getRealmName());
        assertEquals(clone.getAlgorithm(), orig.getAlgorithm());
        assertEquals(clone.getScheme(), orig.getScheme());
    }

    @Test(groups = "standalone")
    public void testOldDigestEmptyString() {
        String qop = "";
        testOldDigest(qop);
    }

    @Test(groups = "standalone")
    public void testOldDigestNull() {
        String qop = null;
        testOldDigest(qop);
    }

    private void testOldDigest(String qop) {
        String user = "user";
        String pass = "pass";
        String realm = "realm";
        String nonce = "nonce";
        String method = "GET";
        Uri uri = Uri.create("http://ahc.io/foo");
        Realm orig = digestAuthRealm(user, pass)//
                .setNonce(nonce)//
                .setUri(uri)//
                .setMethodName(method)//
                .setRealmName(realm)//
                .setQop(qop).build();

        String ha1 = getMd5(user + ":" + realm + ":" + pass);
        String ha2 = getMd5(method + ":" + uri.getPath());
        String expectedResponse = getMd5(ha1 + ":" + nonce + ":" + ha2);

        assertEquals(expectedResponse, orig.getResponse());
    }

    @Test(groups = "standalone")
    public void testStrongDigest() {
        String user = "user";
        String pass = "pass";
        String realm = "realm";
        String nonce = "nonce";
        String method = "GET";
        Uri uri = Uri.create("http://ahc.io/foo");
        String qop = "auth";
        Realm orig = digestAuthRealm(user, pass)//
                .setNonce(nonce)//
                .setUri(uri)//
                .setMethodName(method)//
                .setRealmName(realm)//
                .setQop(qop).build();

        String nc = orig.getNc();
        String cnonce = orig.getCnonce();
        String ha1 = getMd5(user + ":" + realm + ":" + pass);
        String ha2 = getMd5(method + ":" + uri.getPath());
        String expectedResponse = getMd5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);

        assertEquals(expectedResponse, orig.getResponse());
    }

    private String getMd5(String what) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(what.getBytes("ISO-8859-1"));
            byte[] hash = md.digest();
            BigInteger bi = new BigInteger(1, hash);
            String result = bi.toString(16);
            if (result.length() % 2 != 0) {
                return "0" + result;
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
