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

    @Test
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

    @Test
    public void testOldDigestEmptyString() throws Exception {
        testOldDigest("");
    }

    @Test
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

    @Test
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

    @Test
    public void testAuthIntDigestKeepsMethodAndUriInA2() throws Exception {
        String user = "user";
        String pass = "pass";
        String realm = "realm";
        String nonce = "nonce";
        String method = "POST";
        Uri uri = Uri.create("http://ahc.io/foo");
        String qop = "auth-int";
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
        String ha2 = getMd5(method + ':' + uri.getPath() + ':' + getMd5(""));
        String expectedResponse = getMd5(ha1 + ':' + nonce + ':' + nc + ':' + cnonce + ':' + qop + ':' + ha2);

        assertEquals(expectedResponse, orig.getResponse());
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

    /**
     * A Digest challenge we cannot read must not become a Basic one. Answering it as Basic puts the
     * password on the wire in the clear, and a server cannot obtain that by offering Basic outright,
     * because Unauthorized401Interceptor refuses a non-Digest challenge for a Digest realm.
     * <p>
     * Deliberately uses a challenge with no nonce at all, rather than one the parser mishandles, so that
     * this stays a test of the scheme decision alone and cannot be quietly satisfied by the parser.
     */
    @Test
    public void aDigestChallengeWithNoNonceMustNotDowngradeToBasic() {
        Realm realm = new Realm.Builder("user", "pass")
                .parseWWWAuthenticateHeader("Digest realm=\"protected\"")
                .build();

        assertEquals(Realm.AuthScheme.DIGEST, realm.getScheme(),
                "an unreadable Digest challenge must fail, not answer in cleartext");
    }

    @Test
    public void aProxyDigestChallengeWithNoNonceMustNotDowngradeToBasic() {
        Realm realm = new Realm.Builder("user", "pass")
                .parseProxyAuthenticateHeader("Digest realm=\"protected\"")
                .build();

        assertEquals(Realm.AuthScheme.DIGEST, realm.getScheme(),
                "an unreadable proxy Digest challenge must fail, not answer in cleartext");
    }

    /**
     * A genuine Basic challenge is still Basic: the fix above must not turn every challenge into Digest.
     */
    @Test
    public void aBasicChallengeIsStillBasic() {
        Realm realm = new Realm.Builder("user", "pass")
                .parseWWWAuthenticateHeader("Basic realm=\"protected\"")
                .build();

        assertEquals(Realm.AuthScheme.BASIC, realm.getScheme());
    }

    /**
     * A value ending in an unescaped backslash is malformed per RFC 7230 Section 3.2.6, which requires it
     * to be sent as {@code \\}. Every conformant reader, this one and Apache HttpComponents alike, sees the
     * closing quote as escaped and reads on, so the nonce is lost. That is acceptable; answering such a
     * challenge in cleartext is not. The scheme must stay Digest so no Authorization header is produced.
     */
    @Test
    public void aRealmEndingInAnUnescapedBackslashFailsClosed() {
        Realm realm = new Realm.Builder("user", "pass")
                .parseWWWAuthenticateHeader("Digest realm=\"C:\\\", nonce=\"abc123\", qop=\"auth\"")
                .build();

        assertNull(realm.getNonce(), "a malformed challenge is expected to lose the nonce");
        assertEquals(Realm.AuthScheme.DIGEST, realm.getScheme(),
                "losing the nonce must fail the exchange, not downgrade it to Basic");
    }

    /**
     * The conformant spelling of the realm decodes to one backslash.
     */
    @Test
    public void anEscapedBackslashInARealmDecodesToOne() {
        Realm realm = new Realm.Builder("user", "pass")
                .parseWWWAuthenticateHeader("Digest realm=\"DOMAIN\\\\Users\", nonce=\"abc123\"")
                .build();

        assertEquals("DOMAIN\\Users", realm.getRealmName());
        assertEquals("abc123", realm.getNonce());
    }

    /**
     * The unescaped spelling is what real servers tend to send, and it must survive too: a backslash
     * before an ordinary character is literal, so the U of Users cannot be eaten as an escape.
     */
    @Test
    public void anUnescapedBackslashInARealmIsKept() {
        Realm realm = new Realm.Builder("user", "pass")
                .parseWWWAuthenticateHeader("Digest realm=\"DOMAIN\\Users\", nonce=\"abc123\"")
                .build();

        assertEquals("DOMAIN\\Users", realm.getRealmName());
        assertEquals("abc123", realm.getNonce());
    }

    /**
     * A peer that offers only auth-int must not get auth-int negotiated. The rspauth of an auth-int
     * exchange signs the response entity-body, which has not been read when Authentication-Info is
     * processed, so the expected value cannot be derived and mutual authentication ends up skipped for the
     * whole exchange. Offering auth-int alone would therefore let the peer choosing the challenge switch
     * mutual authentication off. Declining to negotiate it keeps that decision on our side.
     */
    @Test
    public void aQopOfAuthIntAloneIsNotNegotiated() {
        Realm realm = new Realm.Builder("user", "pass")
                .parseWWWAuthenticateHeader("Digest realm=\"protected\", nonce=\"N\", qop=\"auth-int\"")
                .build();

        assertNull(realm.getQop(), "auth-int must not be negotiated: its rspauth cannot be verified");
    }

    @Test
    public void authIsStillPreferredWhenBothAreOffered() {
        Realm realm = new Realm.Builder("user", "pass")
                .parseWWWAuthenticateHeader("Digest realm=\"protected\", nonce=\"N\", qop=\"auth,auth-int\"")
                .build();

        assertEquals("auth", realm.getQop());
    }

    private String getMd5(String what) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(what.getBytes(StandardCharsets.ISO_8859_1));
        byte[] hash = md.digest();
        return StringUtils.toHexString(hash);
    }
    /**
     * Realm reaches logs through exception messages and through anything that renders a future, so its
     * toString is a credential sink rather than a debugging convenience.
     */
    @Test
    public void toStringRedactsTheSecretsAndTheUserInfo() {
        Realm realm = digestAuthRealm("user", "hunter2")
                .setUri(Uri.create("https://user:hunter2@example.com/protected"))
                .setResponse("d41d8cd98f00b204e9800998ecf8427e")
                .build();

        String rendered = realm.toString();
        assertFalse(rendered.contains("hunter2"), rendered);
        assertFalse(rendered.contains("d41d8cd98f00b204e9800998ecf8427e"), rendered);
        assertTrue(rendered.contains("principal='user'"), rendered);
        assertTrue(rendered.contains("https://example.com/protected"), rendered);
    }

}
