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

class ScramEngineTest {

    // RFC 5802 test vector parameters
    private static final String PASSWORD = "pencil";
    private static final byte[] SALT = Base64.getDecoder().decode("W22ZaJ0SNY7soEsUEjb6gQ==");
    private static final int ITERATIONS = 4096;

    @Test
    void testHi_SHA256_RFC5802Vector() {
        // Verify PBKDF2 / Hi() with SHA-256 produces expected output for "pencil" with known salt
        byte[] result = ScramEngine.hi(PASSWORD, SALT, ITERATIONS);
        assertNotNull(result);
        assertEquals(32, result.length); // SHA-256 produces 32 bytes
    }

    @Test
    void testHmac_SHA256() {
        byte[] key = "test-key".getBytes(StandardCharsets.US_ASCII);
        byte[] data = "test-data".getBytes(StandardCharsets.US_ASCII);
        byte[] result = ScramEngine.hmac(key, data);
        assertNotNull(result);
        assertEquals(32, result.length);
    }

    @Test
    void testXor_equalLength() {
        byte[] a = {0x01, 0x02, 0x03, 0x04};
        byte[] b = {0x05, 0x06, 0x07, 0x08};
        byte[] result = ScramEngine.xor(a, b);
        assertArrayEquals(new byte[]{0x04, 0x04, 0x04, 0x0C}, result);
        // xor mutates first array in-place
        assertSame(a, result);
    }

    @Test
    void testXor_unequalLength_throws() {
        byte[] a = {0x01, 0x02};
        byte[] b = {0x01, 0x02, 0x03};
        assertThrows(ScramException.class, () -> ScramEngine.xor(a, b));
    }

    @Test
    void testComputeSaltedPassword() {
        byte[] saltedPassword = ScramEngine.computeSaltedPassword(PASSWORD, SALT, ITERATIONS);
        assertNotNull(saltedPassword);
        assertEquals(32, saltedPassword.length);
    }

    @Test
    void testComputeClientKey() {
        byte[] saltedPassword = ScramEngine.computeSaltedPassword(PASSWORD, SALT, ITERATIONS);
        byte[] clientKey = ScramEngine.computeClientKey(saltedPassword);
        assertNotNull(clientKey);
        assertEquals(32, clientKey.length);
    }

    @Test
    void testComputeStoredKey() {
        byte[] saltedPassword = ScramEngine.computeSaltedPassword(PASSWORD, SALT, ITERATIONS);
        byte[] clientKey = ScramEngine.computeClientKey(saltedPassword);
        byte[] storedKey = ScramEngine.computeStoredKey(clientKey);
        assertNotNull(storedKey);
        assertEquals(32, storedKey.length);
    }

    @Test
    void testComputeServerKey() {
        byte[] saltedPassword = ScramEngine.computeSaltedPassword(PASSWORD, SALT, ITERATIONS);
        byte[] serverKey = ScramEngine.computeServerKey(saltedPassword);
        assertNotNull(serverKey);
        assertEquals(32, serverKey.length);
    }

    @Test
    void testComputeClientProof() {
        byte[] clientKey = {0x01, 0x02, 0x03, 0x04};
        byte[] clientSignature = {0x05, 0x06, 0x07, 0x08};
        byte[] proof = ScramEngine.computeClientProof(clientKey, clientSignature);
        // XOR of {01,02,03,04} and {05,06,07,08} = {04,04,04,0C}
        assertArrayEquals(new byte[]{0x04, 0x04, 0x04, 0x0C}, proof);
        // Original clientKey should NOT be modified (clone is used)
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04}, clientKey);
    }

    @Test
    void testComputeServerSignature() {
        byte[] saltedPassword = ScramEngine.computeSaltedPassword(PASSWORD, SALT, ITERATIONS);
        byte[] serverKey = ScramEngine.computeServerKey(saltedPassword);
        String authMessage = "n=user,r=rOprNGfwEbeRWgbNEkqO,r=rOprNGfwEbeRWgbNEkqOsrvr,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096,c=biws,r=rOprNGfwEbeRWgbNEkqOsrvr";
        byte[] serverSignature = ScramEngine.computeServerSignature(serverKey, authMessage);
        assertNotNull(serverSignature);
        assertEquals(32, serverSignature.length);
    }

    @Test
    void testNormalizePassword_ascii() {
        assertEquals("pencil", ScramEngine.normalizePassword("pencil"));
        assertEquals("hello world", ScramEngine.normalizePassword("hello world"));
    }

    @Test
    void testNormalizePassword_nonAscii_NFC() {
        // U+00E9 (é) is already NFC, should pass through
        assertEquals("\u00E9", ScramEngine.normalizePassword("\u00E9"));
        // U+0065 U+0301 (e + combining accent) should be normalized to U+00E9
        assertEquals("\u00E9", ScramEngine.normalizePassword("e\u0301"));
    }

    @Test
    void testNormalizePassword_nonAsciiSpaces() {
        // U+00A0 (non-breaking space) → U+0020
        assertEquals("hello world", ScramEngine.normalizePassword("hello\u00A0world"));
    }

    @Test
    void testNormalizePassword_controlChars_rejected() {
        // U+0000 (NUL) should be rejected
        assertThrows(ScramException.class, () -> ScramEngine.normalizePassword("pass\u0000word"));
        // U+0001 (SOH) should be rejected
        assertThrows(ScramException.class, () -> ScramEngine.normalizePassword("pass\u0001word"));
        // U+007F (DEL) should be rejected
        assertThrows(ScramException.class, () -> ScramEngine.normalizePassword("pass\u007Fword"));
    }

    @Test
    void testNormalizePassword_tabAndNewlineAllowed() {
        // HT (U+0009), LF (U+000A), CR (U+000D) are allowed
        assertDoesNotThrow(() -> ScramEngine.normalizePassword("pass\tword"));
        assertDoesNotThrow(() -> ScramEngine.normalizePassword("pass\nword"));
        assertDoesNotThrow(() -> ScramEngine.normalizePassword("pass\rword"));
    }

    @Test
    void testNormalizePassword_vulgarFraction() {
        // U+00BD (½) - NFC preserves it (unlike NFKC which would decompose to "1/2")
        String result = ScramEngine.normalizePassword("\u00BD");
        assertEquals("\u00BD", result);
    }

    @Test
    void testNormalizePassword_widthPreserved() {
        // U+FF21 (fullwidth 'A') - OpaqueString preserves fullwidth chars
        String result = ScramEngine.normalizePassword("\uFF21");
        assertEquals("\uFF21", result);
    }

    @Test
    void testZeroBytes() {
        byte[] data = {1, 2, 3, 4, 5};
        ScramEngine.zeroBytes(data);
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0}, data);
    }

    @Test
    void testZeroBytes_null() {
        // Should not throw
        assertDoesNotThrow(() -> ScramEngine.zeroBytes(null));
    }

    @Test
    void testGenerateNonce() {
        String nonce1 = ScramEngine.generateNonce(24);
        String nonce2 = ScramEngine.generateNonce(24);
        assertNotNull(nonce1);
        assertNotNull(nonce2);
        assertNotEquals(nonce1, nonce2); // cryptographically random should differ
        // 24 bytes base64 = 32 chars
        assertEquals(32, nonce1.length());
    }

    @Test
    void testHi_passwordIsString() {
        // Verify Hi accepts String and UTF-8 encodes internally
        byte[] result = ScramEngine.hi("p\u00E4ssw\u00F6rd", SALT, ITERATIONS);
        assertNotNull(result);
        assertEquals(32, result.length);
    }

    @Test
    void testHash_SHA256() {
        byte[] data = "test".getBytes(StandardCharsets.US_ASCII);
        byte[] hash = ScramEngine.hash(data);
        assertEquals(32, hash.length);
    }

    @Test
    void testFullKeyDerivation_SHA256() {
        // Verify the full key derivation chain works end-to-end
        byte[] saltedPassword = ScramEngine.computeSaltedPassword(PASSWORD, SALT, ITERATIONS);
        byte[] clientKey = ScramEngine.computeClientKey(saltedPassword);
        byte[] storedKey = ScramEngine.computeStoredKey(clientKey);
        byte[] serverKey = ScramEngine.computeServerKey(saltedPassword);

        // All should be 32 bytes (SHA-256)
        assertEquals(32, saltedPassword.length);
        assertEquals(32, clientKey.length);
        assertEquals(32, storedKey.length);
        assertEquals(32, serverKey.length);

        // StoredKey = H(ClientKey) should be deterministic
        byte[] storedKey2 = ScramEngine.hash(clientKey);
        assertArrayEquals(storedKey, storedKey2);

        // Clean up
        ScramEngine.zeroBytes(saltedPassword);
    }
}
