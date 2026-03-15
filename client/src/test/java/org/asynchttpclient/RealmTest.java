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

import io.github.artsok.RepeatedIfExceptionsTest;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.StringUtils;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static java.nio.charset.StandardCharsets.UTF_16;
import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.digestAuthRealm;
import static org.asynchttpclient.Dsl.realm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RealmTest {

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testClone() {
        Realm orig = basicAuthRealm("user", "pass").setCharset(UTF_16)
                .setUsePreemptiveAuth(true)
                .setRealmName("realm")
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

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testOldDigestEmptyString() throws Exception {
        testOldDigest("");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testOldDigestNull() throws Exception {
        testOldDigest(null);
    }

    private void testOldDigest(String qop) throws Exception {
        String user = "user";
        String pass = "pass";
        String realm = "realm";
        String nonce = "nonce";
        String method = "GET";
        Uri uri = Uri.create("http://ahc.io/foo");
        Realm orig = digestAuthRealm(user, pass)
                .setNonce(nonce)
                .setUri(uri)
                .setMethodName(method)
                .setRealmName(realm)
                .setQop(qop)
                .build();

        String ha1 = getMd5(user + ':' + realm + ':' + pass);
        String ha2 = getMd5(method + ':' + uri.getPath());
        String expectedResponse = getMd5(ha1 + ':' + nonce + ':' + ha2);

        assertEquals(orig.getResponse(), expectedResponse);
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testStrongDigest() throws Exception {
        String user = "user";
        String pass = "pass";
        String realm = "realm";
        String nonce = "nonce";
        String method = "GET";
        Uri uri = Uri.create("http://ahc.io/foo");
        String qop = "auth";
        Realm orig = digestAuthRealm(user, pass)
                .setNonce(nonce)
                .setUri(uri)
                .setMethodName(method)
                .setRealmName(realm)
                .setQop(qop)
                .build();

        String nc = orig.getNc();
        String cnonce = orig.getCnonce();
        String ha1 = getMd5(user + ':' + realm + ':' + pass);
        String ha2 = getMd5(method + ':' + uri.getPath());
        String expectedResponse = getMd5(ha1 + ':' + nonce + ':' + nc + ':' + cnonce + ':' + qop + ':' + ha2);

        assertEquals(orig.getResponse(), expectedResponse);
    }

    // Phase 1: matchParam tests
    @Test
    public void testMatchParamUnquotedAlgorithm() {
        assertEquals("SHA-256", Realm.Builder.matchParam("Digest realm=\"test\", algorithm=SHA-256, qop=\"auth\"", "algorithm"));
    }

    @Test
    public void testMatchParamQuotedAlgorithm() {
        assertEquals("SHA-256", Realm.Builder.matchParam("Digest realm=\"test\", algorithm=\"SHA-256\"", "algorithm"));
    }

    @Test
    public void testMatchParamStale() {
        assertEquals("true", Realm.Builder.matchParam("Digest realm=\"test\", stale=true", "stale"));
    }

    @Test
    public void testMatchParamUserhash() {
        assertEquals("true", Realm.Builder.matchParam("Digest realm=\"test\", userhash=true", "userhash"));
    }

    @Test
    public void testMatchParamMixed() {
        String header = "Digest realm=\"MyRealm\", nonce=\"abc123\", algorithm=SHA-512-256, qop=\"auth,auth-int\", stale=false, userhash=true";
        assertEquals("MyRealm", Realm.Builder.matchParam(header, "realm"));
        assertEquals("abc123", Realm.Builder.matchParam(header, "nonce"));
        assertEquals("SHA-512-256", Realm.Builder.matchParam(header, "algorithm"));
        assertEquals("auth,auth-int", Realm.Builder.matchParam(header, "qop"));
        assertEquals("false", Realm.Builder.matchParam(header, "stale"));
        assertEquals("true", Realm.Builder.matchParam(header, "userhash"));
    }

    @Test
    public void testMatchParamMissing() {
        assertNull(Realm.Builder.matchParam("Digest realm=\"test\"", "algorithm"));
    }

    @Test
    public void testMatchParamNull() {
        assertNull(Realm.Builder.matchParam(null, "realm"));
        assertNull(Realm.Builder.matchParam("Digest realm=\"test\"", null));
    }

    // Phase 2: stale parsing
    @Test
    public void testParseWWWAuthenticateStale() {
        Realm.Builder builder = new Realm.Builder("user", "pass");
        builder.parseWWWAuthenticateHeader("Digest realm=\"test\", nonce=\"abc\", stale=true");
        assertTrue(builder.isStale());
    }

    @Test
    public void testParseWWWAuthenticateStaleNotPresent() {
        Realm.Builder builder = new Realm.Builder("user", "pass");
        builder.parseWWWAuthenticateHeader("Digest realm=\"test\", nonce=\"abc\"");
        assertFalse(builder.isStale());
    }

    @Test
    public void testParseProxyAuthenticateStale() {
        Realm.Builder builder = new Realm.Builder("user", "pass");
        builder.parseProxyAuthenticateHeader("Digest realm=\"test\", nonce=\"abc\", stale=true");
        assertTrue(builder.isStale());
    }

    // Phase 6: userhash parsing
    @Test
    public void testParseWWWAuthenticateUserhash() {
        Realm.Builder builder = new Realm.Builder("user", "pass");
        builder.parseWWWAuthenticateHeader("Digest realm=\"test\", nonce=\"abc\", userhash=true");
        Realm r = builder.build();
        assertTrue(r.isUserhash());
    }

    @Test
    public void testParseProxyAuthenticateUserhash() {
        Realm.Builder builder = new Realm.Builder("user", "pass");
        builder.parseProxyAuthenticateHeader("Digest realm=\"test\", nonce=\"abc\", userhash=true");
        Realm r = builder.build();
        assertTrue(r.isUserhash());
    }

    // Phase 8: Proxy-Authenticate parity (charset + qop parsing)
    @Test
    public void testProxyAuthenticateCharset() {
        Realm.Builder builder = new Realm.Builder("user", "pass");
        builder.parseProxyAuthenticateHeader("Digest realm=\"test\", nonce=\"abc\", charset=UTF-8, qop=\"auth,auth-int\"");
        Realm r = builder.build();
        assertEquals(StandardCharsets.UTF_8, r.getCharset());
        assertEquals("auth", r.getQop()); // auth preferred over auth-int
    }

    // Clone with userhash
    @Test
    public void testCloneWithUserhash() {
        Realm orig = digestAuthRealm("user", "pass")
                .setNonce("nonce")
                .setRealmName("realm")
                .setUserhash(true)
                .build();
        Realm clone = realm(orig).build();
        assertTrue(clone.isUserhash());
        // stale should NOT be copied
        assertFalse(clone.isStale());
    }

    private String getMd5(String what) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(what.getBytes(StandardCharsets.ISO_8859_1));
        byte[] hash = md.digest();
        return StringUtils.toHexString(hash);
    }
}
