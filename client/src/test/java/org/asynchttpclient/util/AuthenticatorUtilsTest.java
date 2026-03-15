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
package org.asynchttpclient.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.request.body.generator.ByteArrayBodyGenerator;
import org.asynchttpclient.request.body.generator.FileBodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AuthenticatorUtilsTest {
    @Test
    void computeBodyHashEmptyBody() throws Exception {
        Request request = new RequestBuilder("GET")
                .setUrl("http://example.com/api/users")
                .build();

        Realm realm = new Realm.Builder("user", "pass")
                .setAlgorithm("MD5")
                .setScheme(Realm.AuthScheme.DIGEST)
                .build();
        String bodyHash = AuthenticatorUtils.computeBodyHash(request, realm);

        String expectedHash = MessageDigestUtils.bytesToHex(
                MessageDigest.getInstance("MD5").digest());
        assertEquals(expectedHash, bodyHash);
    }

    @Test
    void computeBodyHashStringBody_DefaultCharset() throws Exception {
        String Body = "Hello World";

        Request request = new RequestBuilder("POST")
                .setUrl("http://example.com/api/users")
                .setBody(Body)
                .build();

        Realm realm = new Realm.Builder("user", "pass")
                .setAlgorithm("MD5")
                .setScheme(Realm.AuthScheme.DIGEST)
                .build();

        String BodyHash = AuthenticatorUtils.computeBodyHash(request, realm);
        String expectedHash = MessageDigestUtils.bytesToHex(
                MessageDigest.getInstance("MD5").digest(Body.getBytes(StandardCharsets.ISO_8859_1)));

        assertEquals(expectedHash, BodyHash);
    }

    @Test
    void computeBodyHashStringBody_UTF8() throws Exception {
        String Body = "Hello 世界"; //chinese

        Request request = new RequestBuilder("POST")
                .setUrl("http://example.com/api/users")
                .setBody(Body)
                .setCharset(StandardCharsets.UTF_8)
                .build();

        Realm realm = new Realm.Builder("user", "pass")
                .setAlgorithm("MD5")
                .setScheme(Realm.AuthScheme.DIGEST)
                .build();

        String BodyHash = AuthenticatorUtils.computeBodyHash(request, realm);
        String expectedHash = MessageDigestUtils.bytesToHex(
                MessageDigest.getInstance("MD5").digest(Body.getBytes(StandardCharsets.UTF_8)));

        assertEquals(expectedHash, BodyHash);
    }

    @Test
    void computeBodyHashByteArrayBodyGenerator1() throws Exception {
        byte[] body = { 0x01, 0x02, 0x03, 0x04, 0x05 };

        Request request = new RequestBuilder("POST")
                .setUrl("http://example.com/api")
                .setBody(body)                 // builder will wrap this in a ByteArrayBodyGenerator
                .build();

        Realm realm = new Realm.Builder("user", "pass")
                .setScheme(Realm.AuthScheme.DIGEST)
                .setAlgorithm("MD5")
                .build();

        String bodyHash = AuthenticatorUtils.computeBodyHash(request, realm);

        String expected = MessageDigestUtils.bytesToHex(
                MessageDigest.getInstance("MD5").digest(body)
        );
        assertEquals(expected, bodyHash);
    }


    @Test
    void computeBodyHashByteArrayBodyGenerator() throws Exception {
        byte[] body = { 0x01, 0x02, 0x03, 0x04, 0x05 };

        ByteArrayBodyGenerator generator = new ByteArrayBodyGenerator(body);

        Request request = mock(Request.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getBodyGenerator()).thenReturn(generator);
        // all other getters return null
        when(request.getStringData()).thenReturn(null);
        when(request.getByteData()).thenReturn(null);
        when(request.getByteBufData()).thenReturn(null);
        when(request.getByteBufferData()).thenReturn(null);
        when(request.getCharset()).thenReturn(null);

        Realm realm = new Realm.Builder("user", "pass")
                .setScheme(Realm.AuthScheme.DIGEST)
                .setAlgorithm("MD5")
                .build();

        String bodyHash = AuthenticatorUtils.computeBodyHash(request, realm);

        String expected = MessageDigestUtils.bytesToHex(
                MessageDigest.getInstance("MD5").digest(body)
        );
        assertEquals(expected, bodyHash);
    }


    @Test
    void computeBodyHashByteBuf() throws Exception {
        ByteBuf buf = Unpooled.copiedBuffer("ByteBuf Test", StandardCharsets.UTF_8);
        buf.readerIndex(4);                     // advance reader → we should hash only "Buf Test"

        // Mock a Request whose body lives in that ByteBuf
        Request request = mock(Request.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getByteBufData()).thenReturn(buf);
        when(request.getStringData()).thenReturn(null);
        when(request.getByteData()).thenReturn(null);
        when(request.getByteBufferData()).thenReturn(null);
        when(request.getBodyGenerator()).thenReturn(null);
        when(request.getCharset()).thenReturn(null);

        Realm realm = new Realm.Builder("user", "pass")
                .setScheme(Realm.AuthScheme.DIGEST)
                .setAlgorithm("MD5")
                .build();

        try {
            String bodyHash = AuthenticatorUtils.computeBodyHash(request, realm);

            String expected = MessageDigestUtils.bytesToHex(
                    MessageDigest.getInstance("MD5")
                            .digest("Buf Test".getBytes(StandardCharsets.UTF_8))
            );
            assertEquals(expected, bodyHash, "ByteBuf branch produced wrong digest");
            assertEquals(4, buf.readerIndex(), "Reader index must stay unchanged");

        } finally {
            buf.release();
        }
    }

    @Test
    void computeBodyHashByteBuffer() throws Exception {
        // Create ByteBuffer payload
        ByteBuffer bb = ByteBuffer.wrap("ByteBuffer Test".getBytes(StandardCharsets.UTF_8));
        bb.position(5);                               // advance position → helper must hash full content

        // Mock a Request whose body lives in that ByteBuffer
        Request request = mock(Request.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getByteBufferData()).thenReturn(bb);
        when(request.getStringData()).thenReturn(null);
        when(request.getByteData()).thenReturn(null);
        when(request.getByteBufData()).thenReturn(null);
        when(request.getBodyGenerator()).thenReturn(null);
        when(request.getCharset()).thenReturn(null);

        Realm realm = new Realm.Builder("user", "pass")
                .setScheme(Realm.AuthScheme.DIGEST)
                .setAlgorithm("MD5")
                .build();

        String bodyHash = AuthenticatorUtils.computeBodyHash(request, realm);

        // Expected digest of "ByteBuffer Test" (full content)
        String expected = MessageDigestUtils.bytesToHex(
                MessageDigest.getInstance("MD5")
                        .digest("ByteBuffer Test".getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(expected, bodyHash, "ByteBuffer branch produced wrong digest");
        assertEquals(5, bb.position(), "ByteBuffer position must stay unchanged");
    }

    @TempDir Path tempDir;   // JUnit-5-managed temporary folder

    @Test
    void computeBodyHashFileBodyGenerator() throws Exception {
        String content = "File content for testing";
        Path file = tempDir.resolve("test.dat");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        FileBodyGenerator generator = new FileBodyGenerator(file.toFile());

        // Stub Request: only BodyGenerator path is populated
        Request request = mock(Request.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getBodyGenerator()).thenReturn(generator);
        when(request.getStringData()).thenReturn(null);
        when(request.getByteData()).thenReturn(null);
        when(request.getByteBufData()).thenReturn(null);
        when(request.getByteBufferData()).thenReturn(null);
        when(request.getCharset()).thenReturn(null);

        Realm realm = new Realm.Builder("user", "pass")
                .setScheme(Realm.AuthScheme.DIGEST)
                .setAlgorithm("MD5")
                .build();

        String bodyHash = AuthenticatorUtils.computeBodyHash(request, realm);

        // Reference digest
        String expected = MessageDigestUtils.bytesToHex(
                MessageDigest.getInstance("MD5")
                        .digest(content.getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(expected, bodyHash);
    }

    @Test
    void computeBodyHashMultiChunkByteArray() throws Exception {
        // forces three chunks (8 K + 8 K + 4 K)
        byte[] data = new byte[20 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i & 0xFF);
        }

        ByteArrayBodyGenerator generator = new ByteArrayBodyGenerator(data);

        Request request = mock(Request.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getBodyGenerator()).thenReturn(generator);
        when(request.getStringData()).thenReturn(null);
        when(request.getByteData()).thenReturn(null);
        when(request.getByteBufData()).thenReturn(null);
        when(request.getByteBufferData()).thenReturn(null);
        when(request.getCharset()).thenReturn(null);

        Realm realm = new Realm.Builder("user", "pass")
                .setScheme(Realm.AuthScheme.DIGEST)
                .setAlgorithm("MD5")
                .build();

        String hash1 = AuthenticatorUtils.computeBodyHash(request, realm);
        String expected = MessageDigestUtils.bytesToHex(
                MessageDigest.getInstance("MD5").digest(data)
        );
        assertEquals(expected, hash1, "Multi-chunk digest mismatch");

        String hash2 = AuthenticatorUtils.computeBodyHash(request, realm);
        assertEquals(hash1, hash2, "Digest should be reproducible");
        assertEquals(32, hash1.length(), "MD5 hex length");
    }

    @Test
    void byteArrayGeneratorTooLargeThrows() {
        byte[] oversized = new byte[11 * 1024 * 1024];          // 11 MB
        ByteArrayBodyGenerator generator = new ByteArrayBodyGenerator(oversized);

        Request request = mock(Request.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getBodyGenerator()).thenReturn(generator);
        when(request.getStringData()).thenReturn(null);
        when(request.getByteData()).thenReturn(null);
        when(request.getByteBufData()).thenReturn(null);
        when(request.getByteBufferData()).thenReturn(null);
        when(request.getCharset()).thenReturn(null);

        Realm realm = new Realm.Builder("user", "pass")
                .setScheme(Realm.AuthScheme.DIGEST)
                .setAlgorithm("MD5")
                .build();

        assertThrows(UnsupportedOperationException.class, () -> AuthenticatorUtils.computeBodyHash(request, realm));
    }

    @Test
    void fileBodyGeneratorTooLargeThrows() throws Exception {
        // create an 11 MB temp file
        Path bigFile = tempDir.resolve("big.bin");
        try (OutputStream os = Files.newOutputStream(bigFile)) {
            byte[] chunk = new byte[1024 * 1024];          // 1 MB zero-block
            for (int i = 0; i < 11; i++) {
                os.write(chunk);
            }
        }

        FileBodyGenerator generator = new FileBodyGenerator(bigFile.toFile());

        Request request = mock(Request.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getBodyGenerator()).thenReturn(generator);
        when(request.getStringData()).thenReturn(null);
        when(request.getByteData()).thenReturn(null);
        when(request.getByteBufData()).thenReturn(null);
        when(request.getByteBufferData()).thenReturn(null);
        when(request.getCharset()).thenReturn(null);

        Realm realm = new Realm.Builder("user", "pass")
                .setScheme(Realm.AuthScheme.DIGEST)
                .setAlgorithm("MD5")
                .build();

        assertThrows(UnsupportedOperationException.class, () -> AuthenticatorUtils.computeBodyHash(request, realm));
    }

    @Test
    void unsupportedBodyGeneratorThrows() {
        InputStreamBodyGenerator generator = new InputStreamBodyGenerator(new ByteArrayInputStream(new byte[10]));

        Request request = mock(Request.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getBodyGenerator()).thenReturn(generator);
        when(request.getStringData()).thenReturn(null);
        when(request.getByteData()).thenReturn(null);
        when(request.getByteBufData()).thenReturn(null);
        when(request.getByteBufferData()).thenReturn(null);
        when(request.getCharset()).thenReturn(null);

        Realm realm = new Realm.Builder("user", "pass")
                .setScheme(Realm.AuthScheme.DIGEST)
                .setAlgorithm("MD5")
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> AuthenticatorUtils.computeBodyHash(request, realm));
    }

    @Test
    void computeBodyHashSHA256() throws Exception {
        String body = "Test SHA-256";

        Request request = new RequestBuilder("POST")
                .setUrl("http://example.com/api")
                .setBody(body)
                .build();

        Realm realm = new Realm.Builder("user", "pass")
                .setScheme(Realm.AuthScheme.DIGEST)
                .setAlgorithm("SHA-256")
                .build();

        String bodyHash = AuthenticatorUtils.computeBodyHash(request, realm);

        String expected = MessageDigestUtils.bytesToHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(body.getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(expected, bodyHash);
        assertEquals(64, bodyHash.length());
    }

    @Test
    void bytesToHexWorks() {
        byte[] input = {0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF};
        String hex = MessageDigestUtils.bytesToHex(input);
        assertEquals("0123456789abcdef", hex);
    }

    @Test
    void bytesToHexNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> MessageDigestUtils.bytesToHex(null));
    }

    // Phase 5: selectBestDigestChallenge tests
    @Test
    void selectBestDigestChallenge_selectsFirstSupported() {
        List<String> headers = Arrays.asList(
                "Digest realm=\"test\", algorithm=SHA-512-256, nonce=\"a\"",
                "Digest realm=\"test\", algorithm=SHA-256, nonce=\"b\"",
                "Digest realm=\"test\", nonce=\"c\""
        );
        String best = AuthenticatorUtils.selectBestDigestChallenge(headers);
        assertNotNull(best);
        assertTrue(best.contains("SHA-512-256"));
    }

    @Test
    void selectBestDigestChallenge_skipsUnsupported() {
        List<String> headers = Arrays.asList(
                "Digest realm=\"test\", algorithm=SCRAM-SHA-256, nonce=\"a\"",
                "Digest realm=\"test\", algorithm=SHA-256, nonce=\"b\""
        );
        String best = AuthenticatorUtils.selectBestDigestChallenge(headers);
        assertNotNull(best);
        assertTrue(best.contains("SHA-256"));
    }

    @Test
    void selectBestDigestChallenge_defaultsMD5() {
        List<String> headers = List.of("Digest realm=\"test\", nonce=\"a\"");
        String best = AuthenticatorUtils.selectBestDigestChallenge(headers);
        assertNotNull(best);
        // No algorithm specified — defaults to MD5, which is supported
        assertTrue(best.contains("realm=\"test\""));
    }

    @Test
    void selectBestDigestChallenge_skipsNonDigest() {
        List<String> headers = Arrays.asList("Basic realm=\"test\"", "Digest realm=\"test\", nonce=\"a\"");
        String best = AuthenticatorUtils.selectBestDigestChallenge(headers);
        assertNotNull(best);
        assertTrue(best.startsWith("Digest"));
    }

    @Test
    void selectBestDigestChallenge_returnsNull_noDigest() {
        List<String> headers = List.of("Basic realm=\"test\"");
        assertNull(AuthenticatorUtils.selectBestDigestChallenge(headers));
    }

    @Test
    void selectBestDigestChallenge_returnsNull_allUnsupported() {
        List<String> headers = List.of("Digest realm=\"test\", algorithm=UNKNOWN-ALG, nonce=\"a\"");
        assertNull(AuthenticatorUtils.selectBestDigestChallenge(headers));
    }

    // Phase 6: userhash computation
    @Test
    void computeUserhash_md5() throws Exception {
        String result = AuthenticatorUtils.computeUserhash("user", "realm", "MD5", StandardCharsets.ISO_8859_1);
        // H("user:realm") using MD5
        String expected = MessageDigestUtils.bytesToHex(
                MessageDigest.getInstance("MD5").digest("user:realm".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(expected, result);
    }

    @Test
    void computeUserhash_sha256() throws Exception {
        String result = AuthenticatorUtils.computeUserhash("user", "realm", "SHA-256", StandardCharsets.ISO_8859_1);
        String expected = MessageDigestUtils.bytesToHex(
                MessageDigest.getInstance("SHA-256").digest("user:realm".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(expected, result);
        assertEquals(64, result.length()); // SHA-256 hex is 64 chars
    }

    // Phase 7: rspauth computation
    @Test
    void computeRspAuth_basic() throws Exception {
        Realm realm = new Realm.Builder("user", "pass")
                .setScheme(Realm.AuthScheme.DIGEST)
                .setRealmName("testrealm")
                .setNonce("testnonce")
                .setAlgorithm("MD5")
                .setQop("auth")
                .setUri(org.asynchttpclient.uri.Uri.create("http://example.com/path"))
                .build();

        String rspauth = AuthenticatorUtils.computeRspAuth(realm);
        assertNotNull(rspauth);
        assertEquals(32, rspauth.length()); // MD5 hex is 32 chars
    }
}
