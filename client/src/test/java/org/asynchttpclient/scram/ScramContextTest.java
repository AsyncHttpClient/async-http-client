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

class ScramContextTest {

    private static final String USERNAME = "user";
    private static final String PASSWORD = "pencil";
    private static final String REALM = "testrealm@example.com";
    private static final byte[] SALT = Base64.getDecoder().decode("W22ZaJ0SNY7soEsUEjb6gQ==");
    private static final int ITERATIONS = 4096;
    private static final int MAX_ITERATIONS = 16_384;

    @Test
    void testFullExchange_SHA256() {
        // Step 1: Client-first
        ScramContext ctx = new ScramContext(USERNAME, PASSWORD, REALM, "SCRAM-SHA-256");

        assertEquals(ScramState.CLIENT_FIRST_SENT, ctx.getState());
        assertEquals("SCRAM-SHA-256", ctx.getMechanism());
        assertEquals(USERNAME, ctx.getUsername());
        assertEquals(REALM, ctx.getRealmName());

        // Verify client-first-message format
        String clientFirst = ctx.getClientFirstMessage();
        assertTrue(clientFirst.startsWith("n,,n=user,r="));
        String clientFirstBare = ctx.getClientFirstMessageBare();
        assertTrue(clientFirstBare.startsWith("n=user,r="));
        assertEquals("n,," + clientFirstBare, clientFirst);

        // Step 2: Server-first — build a valid server-first-message
        String clientNonce = ctx.getClientNonce();
        String serverNoncePart = "srvr%hvYDpWUa2RaTCAfuxFIlj)hNlF";
        String fullNonce = clientNonce + serverNoncePart;
        String saltBase64 = Base64.getEncoder().encodeToString(SALT);
        String serverFirstMsg = "r=" + fullNonce + ",s=" + saltBase64 + ",i=" + ITERATIONS;

        ctx.processServerFirst(serverFirstMsg, MAX_ITERATIONS);

        assertEquals(ScramState.SERVER_FIRST_RECEIVED, ctx.getState());
        assertEquals(fullNonce, ctx.getServerNonce());
        assertNotNull(ctx.getClientKey());
        assertNotNull(ctx.getStoredKey());
        assertNotNull(ctx.getServerKey());

        // Step 3: Client-final
        String clientFinal = ctx.computeClientFinal();
        assertEquals(ScramState.CLIENT_FINAL_SENT, ctx.getState());
        assertTrue(clientFinal.startsWith("c=biws,r=" + fullNonce + ",p="));

        // Step 4: Verify server-final
        // Compute expected ServerSignature
        byte[] saltedPassword = ScramEngine.computeSaltedPassword(PASSWORD, SALT, ITERATIONS);
        byte[] serverKey = ScramEngine.computeServerKey(saltedPassword);
        String authMessage = clientFirstBare + "," + serverFirstMsg + ","
                + ScramMessageFormatter.clientFinalMessageWithoutProof(fullNonce);
        byte[] serverSignature = ScramEngine.computeServerSignature(serverKey, authMessage);
        String serverFinal = "v=" + Base64.getEncoder().encodeToString(serverSignature);

        assertTrue(ctx.verifyServerFinal(serverFinal));
        assertEquals(ScramState.AUTHENTICATED, ctx.getState());

        ScramEngine.zeroBytes(saltedPassword);
    }

    @Test
    void testProcessServerFirst_iterationCountTooHigh() {
        ScramContext ctx = new ScramContext(USERNAME, PASSWORD, REALM, "SCRAM-SHA-256");
        String clientNonce = ctx.getClientNonce();
        String serverFirstMsg = "r=" + clientNonce + "srvr,s=c2FsdA==,i=100000";

        assertThrows(ScramException.class, () -> ctx.processServerFirst(serverFirstMsg, MAX_ITERATIONS));
    }

    @Test
    void testProcessServerFirst_nonceNotPrefixed() {
        ScramContext ctx = new ScramContext(USERNAME, PASSWORD, REALM, "SCRAM-SHA-256");
        // Server nonce doesn't start with client nonce
        String serverFirstMsg = "r=wrongPrefix,s=c2FsdA==,i=4096";

        assertThrows(ScramException.class, () -> ctx.processServerFirst(serverFirstMsg, MAX_ITERATIONS));
    }

    @Test
    void testVerifyServerFinal_valid() {
        ScramContext ctx = createAuthenticatedContext();
        // Already verified in full exchange test — just verify state
        assertEquals(ScramState.AUTHENTICATED, ctx.getState());
    }

    @Test
    void testVerifyServerFinal_invalid() {
        ScramContext ctx = createContextAtClientFinalSent();
        // Wrong server signature
        assertFalse(ctx.verifyServerFinal("v=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
        assertEquals(ScramState.FAILED, ctx.getState());
    }

    @Test
    void testVerifyServerFinal_serverError() {
        ScramContext ctx = createContextAtClientFinalSent();
        assertFalse(ctx.verifyServerFinal("e=invalid-proof"));
        assertEquals(ScramState.FAILED, ctx.getState());
    }

    @Test
    void testProcessServerFirst_minimumIterationCount() {
        // RFC 5802 doesn't specify a minimum iteration count.
        // Server sends i=1 — should be accepted (documents that no minimum is enforced).
        ScramContext ctx = new ScramContext(USERNAME, PASSWORD, REALM, "SCRAM-SHA-256");
        String clientNonce = ctx.getClientNonce();
        String fullNonce = clientNonce + "serverpart";
        String saltBase64 = Base64.getEncoder().encodeToString(SALT);
        String serverFirstMsg = "r=" + fullNonce + ",s=" + saltBase64 + ",i=1";

        // Should not throw — i=1 is accepted
        assertDoesNotThrow(() -> ctx.processServerFirst(serverFirstMsg, MAX_ITERATIONS));
        assertEquals(ScramState.SERVER_FIRST_RECEIVED, ctx.getState());
        assertEquals(1, ctx.getIterationCount());
    }

    @Test
    void testGetClientFirstMessage_includesGs2Header() {
        ScramContext ctx = new ScramContext(USERNAME, PASSWORD, REALM, "SCRAM-SHA-256");
        assertTrue(ctx.getClientFirstMessage().startsWith("n,,"));
    }

    @Test
    void testToSessionCacheEntry() {
        ScramContext ctx = createAuthenticatedContext();
        ScramSessionCache.Entry entry = ctx.toSessionCacheEntry("serverNonce123", 300);
        assertEquals(REALM, entry.realmName);
        assertNotNull(entry.clientKey);
        assertNotNull(entry.storedKey);
        assertNotNull(entry.serverKey);
        assertEquals("serverNonce123", entry.sr);
        assertEquals(300, entry.ttl);
        assertEquals(ITERATIONS, entry.nonceCount.get());
        assertNotNull(entry.originalServerFirstMessage);
    }

    // Helper methods

    private ScramContext createContextAtClientFinalSent() {
        ScramContext ctx = new ScramContext(USERNAME, PASSWORD, REALM, "SCRAM-SHA-256");
        String clientNonce = ctx.getClientNonce();
        String fullNonce = clientNonce + "serverpart";
        String saltBase64 = Base64.getEncoder().encodeToString(SALT);
        String serverFirstMsg = "r=" + fullNonce + ",s=" + saltBase64 + ",i=" + ITERATIONS;
        ctx.processServerFirst(serverFirstMsg, MAX_ITERATIONS);
        ctx.computeClientFinal();
        return ctx;
    }

    private ScramContext createAuthenticatedContext() {
        ScramContext ctx = new ScramContext(USERNAME, PASSWORD, REALM, "SCRAM-SHA-256");
        String clientNonce = ctx.getClientNonce();
        String fullNonce = clientNonce + "serverpart";
        String saltBase64 = Base64.getEncoder().encodeToString(SALT);
        String serverFirstMsg = "r=" + fullNonce + ",s=" + saltBase64 + ",i=" + ITERATIONS;

        ctx.processServerFirst(serverFirstMsg, MAX_ITERATIONS);
        ctx.computeClientFinal();

        // Compute correct server signature
        byte[] saltedPassword = ScramEngine.computeSaltedPassword(PASSWORD, SALT, ITERATIONS);
        byte[] serverKey = ScramEngine.computeServerKey(saltedPassword);
        String authMessage = ctx.getClientFirstMessageBare() + "," + serverFirstMsg + ","
                + ctx.getClientFinalMessageWithoutProof();
        byte[] serverSignature = ScramEngine.computeServerSignature(serverKey, authMessage);
        String serverFinal = "v=" + Base64.getEncoder().encodeToString(serverSignature);
        ctx.verifyServerFinal(serverFinal);
        ScramEngine.zeroBytes(saltedPassword);
        return ctx;
    }
}
