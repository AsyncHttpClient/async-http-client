/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.scram;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ScramMessageParserTest {

    @Test
    void testParseServerFirst_valid() {
        String message = "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096";
        ScramMessageParser.ServerFirstMessage result = ScramMessageParser.parseServerFirst(message);
        assertEquals("rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF", result.fullNonce);
        assertArrayEquals(Base64.getDecoder().decode("W22ZaJ0SNY7soEsUEjb6gQ=="), result.salt);
        assertEquals(4096, result.iterationCount);
    }

    @Test
    void testParseServerFirst_missingNonce() {
        assertThrows(ScramException.class, () ->
                ScramMessageParser.parseServerFirst("s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"));
    }

    @Test
    void testParseServerFirst_missingSalt() {
        assertThrows(ScramException.class, () ->
                ScramMessageParser.parseServerFirst("r=rOprNGfwEbeRWgbNEkqOsrvr,i=4096"));
    }

    @Test
    void testParseServerFirst_missingIteration() {
        assertThrows(ScramException.class, () ->
                ScramMessageParser.parseServerFirst("r=rOprNGfwEbeRWgbNEkqOsrvr,s=W22ZaJ0SNY7soEsUEjb6gQ=="));
    }

    @Test
    void testParseServerFirst_withExtensions() {
        // Extensions after i= should be tolerated
        String message = "r=nonce123srvr,s=c2FsdA==,i=4096,ext=value";
        ScramMessageParser.ServerFirstMessage result = ScramMessageParser.parseServerFirst(message);
        assertEquals("nonce123srvr", result.fullNonce);
        assertEquals(4096, result.iterationCount);
    }

    @Test
    void testParseServerFirst_invalidIterationCount() {
        assertThrows(ScramException.class, () ->
                ScramMessageParser.parseServerFirst("r=nonce,s=c2FsdA==,i=abc"));
    }

    @Test
    void testParseServerFirst_zeroIterationCount() {
        assertThrows(ScramException.class, () ->
                ScramMessageParser.parseServerFirst("r=nonce,s=c2FsdA==,i=0"));
    }

    @Test
    void testParseServerFirst_invalidBase64Salt() {
        assertThrows(ScramException.class, () ->
                ScramMessageParser.parseServerFirst("r=nonce,s=!!!invalid!!!,i=4096"));
    }

    @Test
    void testParseServerFinal_verifier() {
        String message = "v=rmF9pqV8S7suAoZWja4dJRkFsKQ=";
        ScramMessageParser.ServerFinalMessage result = ScramMessageParser.parseServerFinal(message);
        assertEquals("rmF9pqV8S7suAoZWja4dJRkFsKQ=", result.verifier);
        assertNull(result.error);
    }

    @Test
    void testParseServerFinal_error() {
        String message = "e=invalid-proof";
        ScramMessageParser.ServerFinalMessage result = ScramMessageParser.parseServerFinal(message);
        assertNull(result.verifier);
        assertEquals("invalid-proof", result.error);
    }

    @Test
    void testParseServerFinal_invalid() {
        assertThrows(ScramException.class, () ->
                ScramMessageParser.parseServerFinal("x=unknown"));
    }

    @Test
    void testParseWwwAuthenticate_realmAndData() {
        String header = "SCRAM-SHA-256 realm=\"testrealm@example.com\", data=\"biwsbj11c2VyLHI9ck9wck5HZndFYmVSV2diTkVrcU8=\"";
        ScramMessageParser.ScramChallengeParams params = ScramMessageParser.parseWwwAuthenticateScram(header);
        assertEquals("testrealm@example.com", params.realm);
        assertEquals("biwsbj11c2VyLHI9ck9wck5HZndFYmVSV2diTkVrcU8=", params.data);
        assertNull(params.sid);
        assertFalse(params.stale);
    }

    @Test
    void testParseWwwAuthenticate_sidAndData() {
        String header = "SCRAM-SHA-256 sid=AAAABBBB, data=\"cj1yT3ByTkdmd0ViZQ==\"";
        ScramMessageParser.ScramChallengeParams params = ScramMessageParser.parseWwwAuthenticateScram(header);
        assertEquals("AAAABBBB", params.sid);
        assertEquals("cj1yT3ByTkdmd0ViZQ==", params.data);
    }

    @Test
    void testParseWwwAuthenticate_srAndTtl() {
        String header = "SCRAM-SHA-256 realm=\"test\", sr=serverNonce123, ttl=300";
        ScramMessageParser.ScramChallengeParams params = ScramMessageParser.parseWwwAuthenticateScram(header);
        assertEquals("test", params.realm);
        assertEquals("serverNonce123", params.sr);
        assertEquals(300, params.ttl);
    }

    @Test
    void testParseWwwAuthenticate_staleFlag() {
        String header = "SCRAM-SHA-256 realm=\"test\", sr=nonce, stale=true";
        ScramMessageParser.ScramChallengeParams params = ScramMessageParser.parseWwwAuthenticateScram(header);
        assertTrue(params.stale);

        header = "SCRAM-SHA-256 realm=\"test\", sr=nonce, stale=false";
        params = ScramMessageParser.parseWwwAuthenticateScram(header);
        assertFalse(params.stale);
    }

    @Test
    void testParseWwwAuthenticate_noTtl() {
        String header = "SCRAM-SHA-256 realm=\"test\"";
        ScramMessageParser.ScramChallengeParams params = ScramMessageParser.parseWwwAuthenticateScram(header);
        assertEquals(-1, params.ttl);
    }

    @Test
    void testParseServerFirst_emptySalt() {
        assertThrows(ScramException.class, () ->
                ScramMessageParser.parseServerFirst("r=nonce,s=,i=4096"));
    }

    @Test
    void testValidateGs2Header_valid() {
        assertDoesNotThrow(() -> ScramMessageParser.validateGs2Header("n,,n=user,r=nonce"));
    }

    @Test
    void testValidateGs2Header_invalid_y() {
        assertThrows(ScramException.class, () -> ScramMessageParser.validateGs2Header("y,,n=user,r=nonce"));
    }

    @Test
    void testValidateGs2Header_invalid_p() {
        assertThrows(ScramException.class, () -> ScramMessageParser.validateGs2Header("p=tls-unique,,n=user,r=nonce"));
    }

    @Test
    void testValidateNoncePrefix_valid() {
        assertDoesNotThrow(() -> ScramMessageParser.validateNoncePrefix("clientNonce", "clientNonceServerPart"));
    }

    @Test
    void testValidateNoncePrefix_invalid() {
        assertThrows(ScramException.class, () -> ScramMessageParser.validateNoncePrefix("clientNonce", "differentNonce"));
    }

    @Test
    void testValidateNoncePrefix_empty_server_part() {
        assertThrows(ScramException.class, () -> ScramMessageParser.validateNoncePrefix("clientNonce", "clientNonce"));
    }
}
