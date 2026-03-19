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

class ScramMessageFormatterTest {

    @Test
    void testFormatClientFirst() {
        String result = ScramMessageFormatter.formatClientFirstMessage("user", "rOprNGfwEbeRWgbNEkqO");
        assertEquals("n,,n=user,r=rOprNGfwEbeRWgbNEkqO", result);
    }

    @Test
    void testFormatClientFirst_usernameEscaping() {
        // "=" → "=3D", "," → "=2C"
        String result = ScramMessageFormatter.formatClientFirstMessage("us=er,name", "nonce123");
        assertEquals("n,,n=us=3Der=2Cname,r=nonce123", result);
    }

    @Test
    void testClientFirstMessageBare() {
        String result = ScramMessageFormatter.clientFirstMessageBare("user", "rOprNGfwEbeRWgbNEkqO");
        assertEquals("n=user,r=rOprNGfwEbeRWgbNEkqO", result);
    }

    @Test
    void testGetClientFirstMessage_includesGs2Header() {
        String full = ScramMessageFormatter.formatClientFirstMessage("user", "nonce");
        assertTrue(full.startsWith("n,,"), "Full message must start with gs2-header 'n,,'");
        String bare = ScramMessageFormatter.clientFirstMessageBare("user", "nonce");
        assertEquals("n,," + bare, full);
    }

    @Test
    void testFormatClientFinal() {
        byte[] proof = {0x01, 0x02, 0x03, 0x04};
        String result = ScramMessageFormatter.formatClientFinalMessage("fullNonce123", proof);
        String expectedProof = Base64.getEncoder().encodeToString(proof);
        assertEquals("c=biws,r=fullNonce123,p=" + expectedProof, result);
    }

    @Test
    void testClientFinalMessageWithoutProof() {
        String result = ScramMessageFormatter.clientFinalMessageWithoutProof("fullNonce123");
        assertEquals("c=biws,r=fullNonce123", result);
    }

    @Test
    void testFormatAuthorizationHeader_initial() {
        // Erratum 6558: data attribute MUST be quoted
        String result = ScramMessageFormatter.formatAuthorizationHeader(
                "SCRAM-SHA-256", "testrealm@example.com", null, "biwsbj11c2VyLHI9bm9uY2U=");
        assertEquals("SCRAM-SHA-256 realm=\"testrealm@example.com\", data=\"biwsbj11c2VyLHI9bm9uY2U=\"", result);
        // Verify data is quoted
        assertTrue(result.contains("data=\""));
    }

    @Test
    void testFormatAuthorizationHeader_final() {
        String result = ScramMessageFormatter.formatAuthorizationHeader(
                "SCRAM-SHA-256", null, "AAAABBBB", "Yz1iaXdzLHI9bm9uY2U=");
        assertEquals("SCRAM-SHA-256 sid=AAAABBBB, data=\"Yz1iaXdzLHI9bm9uY2U=\"", result);
    }

    @Test
    void testFormatAuthorizationHeader_reauth() {
        String result = ScramMessageFormatter.formatAuthorizationHeader(
                "SCRAM-SHA-256", "myrealm", null, "base64data");
        assertEquals("SCRAM-SHA-256 realm=\"myrealm\", data=\"base64data\"", result);
    }

    @Test
    void testEscapeUsername() {
        assertEquals("user", ScramMessageFormatter.escapeUsername("user"));
        assertEquals("us=3Der", ScramMessageFormatter.escapeUsername("us=er"));
        assertEquals("us=2Cer", ScramMessageFormatter.escapeUsername("us,er"));
        assertEquals("=3D=2C", ScramMessageFormatter.escapeUsername("=,"));
    }

    @Test
    void testBase64_standardAlphabet() {
        // Verify base64 uses standard alphabet (+/) not URL-safe (-_)
        byte[] data = {(byte) 0xFB, (byte) 0xEF, (byte) 0xBE};
        String encoded = Base64.getEncoder().encodeToString(data);
        assertTrue(encoded.contains("+") || encoded.contains("/"),
                "Standard base64 should use +/ characters, not URL-safe -_");
        assertFalse(encoded.contains("-"), "Must not use URL-safe base64");
        assertFalse(encoded.contains("_"), "Must not use URL-safe base64");
    }

    @Test
    void testChannelBindingValue() {
        // "c=biws" is base64 of "n,," (the gs2-header for no channel binding)
        String decoded = new String(Base64.getDecoder().decode("biws"));
        assertEquals("n,,", decoded);
    }
}
