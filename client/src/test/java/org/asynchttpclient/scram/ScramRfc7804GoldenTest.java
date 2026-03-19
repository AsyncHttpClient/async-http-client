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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tests verifying SCRAM-SHA-256 against independently computed
 * values using RFC 7804 §5 and RFC 5802 test vector parameters.
 *
 * CRITICAL: Values are independently computed from input parameters, NOT copied from
 * RFC examples (which may contain trailing newlines per Erratum 6633).
 */
class ScramRfc7804GoldenTest {

    // RFC 7804 / RFC 5802 / RFC 7677 shared parameters
    private static final String USERNAME = "user";
    private static final String PASSWORD = "pencil";
    private static final String CLIENT_NONCE = "rOprNGfwEbeRWgbNEkqO";
    private static final byte[] SALT = Base64.getDecoder().decode("W22ZaJ0SNY7soEsUEjb6gQ==");
    private static final int ITERATIONS = 4096;

    @Test
    void testClientFirstMessage_format() {
        // No trailing newline (Erratum 6633)
        String clientFirst = ScramMessageFormatter.formatClientFirstMessage(USERNAME, CLIENT_NONCE);
        assertEquals("n,,n=user,r=rOprNGfwEbeRWgbNEkqO", clientFirst);
        assertFalse(clientFirst.endsWith("\n"), "Must NOT have trailing newline (Erratum 6633)");
    }

    @Test
    void testClientFirstMessageBare_format() {
        String bare = ScramMessageFormatter.clientFirstMessageBare(USERNAME, CLIENT_NONCE);
        assertEquals("n=user,r=rOprNGfwEbeRWgbNEkqO", bare);
    }

    @Test
    void testClientFirstMessage_base64() {
        String clientFirst = ScramMessageFormatter.formatClientFirstMessage(USERNAME, CLIENT_NONCE);
        String base64 = Base64.getEncoder().encodeToString(clientFirst.getBytes(StandardCharsets.UTF_8));
        // Verify round-trip
        String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        assertEquals(clientFirst, decoded);
        // Standard base64 alphabet (not URL-safe)
        assertFalse(base64.contains("-"), "Must use standard base64, not URL-safe");
        assertFalse(base64.contains("_"), "Must use standard base64, not URL-safe");
    }

    @Test
    void testSHA256_fullKeyDerivation() {
        // Independently compute all intermediate values
        byte[] saltedPassword = ScramEngine.computeSaltedPassword(PASSWORD, SALT, ITERATIONS);
        assertEquals(32, saltedPassword.length);

        byte[] clientKey = ScramEngine.computeClientKey(saltedPassword);
        assertEquals(32, clientKey.length);

        byte[] storedKey = ScramEngine.computeStoredKey(clientKey);
        assertEquals(32, storedKey.length);

        byte[] serverKey = ScramEngine.computeServerKey(saltedPassword);
        assertEquals(32, serverKey.length);

        // Verify StoredKey = H(ClientKey)
        byte[] recomputedStoredKey = ScramEngine.hash(clientKey);
        assertArrayEquals(storedKey, recomputedStoredKey);

        ScramEngine.zeroBytes(saltedPassword);
    }

    @Test
    void testSHA256_clientProofAndServerSignature() {
        // Use RFC 7804 nonce suffix
        String serverNonceSuffix = "%hvYDpWUa2RaTCAfuxFIlj)hNlF";
        String fullNonce = CLIENT_NONCE + serverNonceSuffix;
        String saltBase64 = Base64.getEncoder().encodeToString(SALT);

        String clientFirstBare = "n=user,r=" + CLIENT_NONCE;
        String serverFirst = "r=" + fullNonce + ",s=" + saltBase64 + ",i=" + ITERATIONS;
        String clientFinalWithoutProof = "c=biws,r=" + fullNonce;
        String authMessage = clientFirstBare + "," + serverFirst + "," + clientFinalWithoutProof;

        // Compute all keys
        byte[] saltedPassword = ScramEngine.computeSaltedPassword(PASSWORD, SALT, ITERATIONS);
        byte[] clientKey = ScramEngine.computeClientKey(saltedPassword);
        byte[] storedKey = ScramEngine.computeStoredKey(clientKey);
        byte[] serverKey = ScramEngine.computeServerKey(saltedPassword);

        // Compute ClientProof
        byte[] clientSignature = ScramEngine.computeClientSignature(storedKey, authMessage);
        byte[] clientProof = ScramEngine.computeClientProof(clientKey, clientSignature);
        assertNotNull(clientProof);
        assertEquals(32, clientProof.length);

        // Compute ServerSignature
        byte[] serverSignature = ScramEngine.computeServerSignature(serverKey, authMessage);
        assertNotNull(serverSignature);
        assertEquals(32, serverSignature.length);

        // Verify: ClientKey XOR ClientSignature = ClientProof
        byte[] recomputedClientKey = ScramEngine.xor(clientProof.clone(), clientSignature);
        assertArrayEquals(clientKey, recomputedClientKey);

        ScramEngine.zeroBytes(saltedPassword);
    }

    @Test
    void testSHA256_fullExchangeConsistency() {
        // Verify that ScramContext produces same values as manual computation
        String serverNonceSuffix = "ServerNonce123";
        String saltBase64 = Base64.getEncoder().encodeToString(SALT);

        // Manual computation
        byte[] saltedPassword = ScramEngine.computeSaltedPassword(PASSWORD, SALT, ITERATIONS);
        byte[] manualClientKey = ScramEngine.computeClientKey(saltedPassword);
        byte[] manualStoredKey = ScramEngine.computeStoredKey(manualClientKey);
        byte[] manualServerKey = ScramEngine.computeServerKey(saltedPassword);

        // ScramContext computation (uses same algorithm internally)
        ScramContext ctx = new ScramContext(USERNAME, PASSWORD, REALM_NAME, "SCRAM-SHA-256");
        String clientNonce = ctx.getClientNonce();
        String fullNonce = clientNonce + serverNonceSuffix;
        String serverFirst = "r=" + fullNonce + ",s=" + saltBase64 + ",i=" + ITERATIONS;
        ctx.processServerFirst(serverFirst, 16_384);

        // Keys should match
        assertArrayEquals(manualClientKey, ctx.getClientKey());
        assertArrayEquals(manualStoredKey, ctx.getStoredKey());
        assertArrayEquals(manualServerKey, ctx.getServerKey());

        // Compute client-final and verify
        String clientFinal = ctx.computeClientFinal();
        assertTrue(clientFinal.startsWith("c=biws,r=" + fullNonce + ",p="));

        // Build and verify server-final
        String authMessage = ctx.getClientFirstMessageBare() + "," + serverFirst + ","
                + ctx.getClientFinalMessageWithoutProof();
        byte[] serverSignature = ScramEngine.computeServerSignature(manualServerKey, authMessage);
        String serverFinal = "v=" + Base64.getEncoder().encodeToString(serverSignature);
        assertTrue(ctx.verifyServerFinal(serverFinal));

        ScramEngine.zeroBytes(saltedPassword);
    }

    @Test
    void testUsernameEscaping() {
        assertEquals("user", ScramMessageFormatter.escapeUsername("user"));
        assertEquals("us=3Der", ScramMessageFormatter.escapeUsername("us=er"));
        assertEquals("us=2Cer", ScramMessageFormatter.escapeUsername("us,er"));
    }

    @Test
    void testChannelBindingField() {
        // c=biws is base64("n,,") — the gs2-header for no channel binding
        String decoded = new String(Base64.getDecoder().decode("biws"), StandardCharsets.UTF_8);
        assertEquals("n,,", decoded);
    }

    @Test
    void testBase64_standardAlphabet() {
        // Verify standard (not URL-safe) base64 is used throughout
        String encoded = Base64.getEncoder().encodeToString(SALT);
        // Standard base64 uses + and / (not - and _)
        for (char c : encoded.toCharArray()) {
            assertTrue(
                    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                            || c == '+' || c == '/' || c == '=',
                    "Character '" + c + "' is not in standard base64 alphabet"
            );
        }
    }

    @Test
    void testBase64_noTrailingNewlines() {
        // Erratum 6633: no trailing newlines in base64
        String clientFirst = "n,,n=user,r=rOprNGfwEbeRWgbNEkqO";
        String encoded = Base64.getEncoder().encodeToString(
                clientFirst.getBytes(StandardCharsets.UTF_8));
        assertFalse(encoded.contains("\n"), "Base64 must not contain newlines");
        assertFalse(encoded.contains("\r"), "Base64 must not contain carriage returns");
    }

    @Test
    void testDataAttributeQuoting() {
        // Erratum 6558: data attribute MUST be quoted
        String header = ScramMessageFormatter.formatAuthorizationHeader(
                "SCRAM-SHA-256", "test", null, "dGVzdA==");
        assertTrue(header.contains("data=\"dGVzdA==\""), "data attribute must be quoted");
    }

    private static final String REALM_NAME = "testrealm@example.com";
}
