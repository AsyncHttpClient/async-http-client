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

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;

/**
 * Core SCRAM-SHA-256 cryptographic operations (RFC 5802, RFC 7804).
 * Thread-safe: all methods are stateless.
 */
public final class ScramEngine {

    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String HASH_ALGO = "SHA-256";
    private static final int KEY_LENGTH_BITS = 256;

    private static final ThreadLocal<SecureRandom> SECURE_RANDOM = ThreadLocal.withInitial(SecureRandom::new);

    private ScramEngine() {
    }

    /**
     * PBKDF2 / Hi() computation as defined in RFC 5802.
     * Hi(str, salt, i) = PBKDF2(str, salt, i, dkLen)
     *
     * @param normalizedPassword the normalized password (String, UTF-8 encoded internally)
     * @param salt               the salt bytes
     * @param iterations         the iteration count
     * @return the derived key bytes
     */
    public static byte[] hi(String normalizedPassword, byte[] salt, int iterations) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGO);
            PBEKeySpec spec = new PBEKeySpec(normalizedPassword.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
            try {
                return factory.generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new ScramException("PBKDF2 computation failed", e);
        }
    }

    /**
     * Compute HMAC-SHA-256.
     */
    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key, HMAC_ALGO));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new ScramException("HMAC computation failed", e);
        }
    }

    /**
     * Compute SHA-256 hash.
     */
    public static byte[] hash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH_ALGO);
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new ScramException("Hash computation failed", e);
        }
    }

    /**
     * XOR two equal-length byte arrays. Mutates array {@code a} in-place.
     */
    public static byte[] xor(byte[] a, byte[] b) {
        if (a.length != b.length) {
            throw new ScramException("XOR operands must have equal length: " + a.length + " vs " + b.length);
        }
        for (int i = 0; i < a.length; i++) {
            a[i] ^= b[i];
        }
        return a;
    }

    /**
     * Generate a cryptographically random nonce, base64-encoded.
     *
     * @param lengthBytes number of random bytes (before base64 encoding)
     * @return base64-encoded nonce string
     */
    public static String generateNonce(int lengthBytes) {
        byte[] bytes = new byte[lengthBytes];
        SECURE_RANDOM.get().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Normalize password per RFC 7613 OpaqueString profile.
     * Steps applied in order: 1) width mapping (preserve), 2) non-ASCII spaces to U+0020,
     * 3) case preserved, 4) NFC normalization, 5) prohibited char check.
     */
    public static String normalizePassword(String password) {
        if (password == null || password.isEmpty()) {
            return password;
        }

        // Steps 1-2: Width mapping (preserve) + map non-ASCII spaces to U+0020
        StringBuilder sb = new StringBuilder(password.length());
        for (int i = 0; i < password.length(); ) {
            int cp = password.codePointAt(i);

            // Step 2: Map non-ASCII spaces (Unicode Zs category except U+0020) to U+0020
            if (cp != 0x0020 && Character.getType(cp) == Character.SPACE_SEPARATOR) {
                sb.append(' ');
            } else {
                sb.appendCodePoint(cp);
            }

            i += Character.charCount(cp);
        }

        // Step 3: Case preserved (no-op)

        // Step 4: Apply NFC normalization
        String normalized = Normalizer.normalize(sb, Normalizer.Form.NFC);

        // Step 5: Reject prohibited characters (PRECIS FreeformClass) — after NFC
        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            if (isProhibited(cp)) {
                throw new ScramException("Password contains prohibited character: U+" + String.format("%04X", cp));
            }
            i += Character.charCount(cp);
        }

        return normalized;
    }

    /**
     * Check if a codepoint is prohibited by PRECIS FreeformClass (RFC 8264 §9.11).
     */
    private static boolean isProhibited(int cp) {
        // Control characters (except HT, LF, CR)
        if (cp <= 0x001F && cp != 0x0009 && cp != 0x000A && cp != 0x000D) {
            return true;
        }
        if (cp == 0x007F) {
            return true; // DEL
        }
        if (cp >= 0x0080 && cp <= 0x009F) {
            return true; // C1 control characters
        }

        // Surrogates (should not appear in valid Java strings, but check anyway)
        if (cp >= 0xD800 && cp <= 0xDFFF) {
            return true;
        }

        // Non-characters
        if (cp >= 0xFDD0 && cp <= 0xFDEF) {
            return true;
        }
        if ((cp & 0xFFFE) == 0xFFFE && cp <= 0x10FFFF) {
            return true; // U+xFFFE and U+xFFFF for any plane
        }

        // Unassigned codepoints
        int type = Character.getType(cp);
        if (type == Character.UNASSIGNED) {
            return true;
        }

        return false;
    }

    /**
     * SaltedPassword := Hi(Normalize(password), salt, i)
     */
    public static byte[] computeSaltedPassword(String password, byte[] salt, int iterations) {
        String normalized = normalizePassword(password);
        return hi(normalized, salt, iterations);
    }

    /**
     * ClientKey := HMAC(SaltedPassword, "Client Key")
     */
    public static byte[] computeClientKey(byte[] saltedPassword) {
        return hmac(saltedPassword, "Client Key".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * StoredKey := H(ClientKey)
     */
    public static byte[] computeStoredKey(byte[] clientKey) {
        return hash(clientKey);
    }

    /**
     * ServerKey := HMAC(SaltedPassword, "Server Key")
     */
    public static byte[] computeServerKey(byte[] saltedPassword) {
        return hmac(saltedPassword, "Server Key".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * ClientSignature := HMAC(StoredKey, AuthMessage)
     */
    public static byte[] computeClientSignature(byte[] storedKey, String authMessage) {
        return hmac(storedKey, authMessage.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * ClientProof := ClientKey XOR ClientSignature
     */
    public static byte[] computeClientProof(byte[] clientKey, byte[] clientSignature) {
        return xor(clientKey.clone(), clientSignature);
    }

    /**
     * ServerSignature := HMAC(ServerKey, AuthMessage)
     */
    public static byte[] computeServerSignature(byte[] serverKey, String authMessage) {
        return hmac(serverKey, authMessage.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Zero out a byte array for security.
     */
    public static void zeroBytes(byte[] array) {
        if (array != null) {
            Arrays.fill(array, (byte) 0);
        }
    }
}
