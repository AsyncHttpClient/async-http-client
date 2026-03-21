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

import org.jetbrains.annotations.Nullable;

import java.security.MessageDigest;
import java.util.Base64;

import static java.util.Objects.requireNonNull;

/**
 * Per-exchange mutable state for a SCRAM authentication handshake (RFC 7804).
 * Attached to NettyResponseFuture during a SCRAM exchange.
 * Not thread-safe: accessed only from EventLoop.
 */
public class ScramContext {

    private ScramState state;
    private final String mechanism;
    private final String username;
    private @Nullable String password;
    private final @Nullable String realmName;
    private final String clientNonce;
    private @Nullable String serverNonce;
    private @Nullable String sid;
    private @Nullable byte[] salt;
    private int iterationCount;
    private final String clientFirstMessage;
    private final String clientFirstMessageBare;
    private @Nullable String serverFirstMessage;
    private @Nullable String clientFinalMessageWithoutProof;
    private @Nullable byte[] clientKey;
    private @Nullable byte[] storedKey;
    private @Nullable byte[] serverKey;
    private @Nullable ScramMessageParser.ScramChallengeParams initialChallengeParams;

    /**
     * Create a ScramContext and initialize the client-first step.
     */
    public ScramContext(String username, String password, @Nullable String realmName, String mechanism) {
        this.username = username;
        this.password = password;
        this.realmName = realmName;
        this.mechanism = mechanism;
        this.clientNonce = ScramEngine.generateNonce(24);
        this.clientFirstMessage = ScramMessageFormatter.formatClientFirstMessage(username, clientNonce);
        this.clientFirstMessageBare = ScramMessageFormatter.clientFirstMessageBare(username, clientNonce);
        this.state = ScramState.CLIENT_FIRST_SENT;
    }

    /**
     * Process the server-first-message: validate nonce, extract salt/iterations,
     * compute derived keys, and zero SaltedPassword.
     *
     * @param serverFirstMsg      the verbatim server-first-message (decoded from base64)
     * @param maxIterationCount   maximum allowed iteration count for DoS protection
     */
    public void processServerFirst(String serverFirstMsg, int maxIterationCount) {
        this.serverFirstMessage = serverFirstMsg;

        ScramMessageParser.ServerFirstMessage parsed = ScramMessageParser.parseServerFirst(serverFirstMsg);

        // Validate nonce prefix
        ScramMessageParser.validateNoncePrefix(clientNonce, parsed.fullNonce);

        // Validate iteration count
        if (parsed.iterationCount > maxIterationCount) {
            throw new ScramException("Server iteration count " + parsed.iterationCount
                    + " exceeds maximum allowed " + maxIterationCount);
        }

        this.serverNonce = parsed.fullNonce;
        this.salt = parsed.salt;
        this.iterationCount = parsed.iterationCount;

        // Compute derived keys
        String pwd = requireNonNull(password, "password already consumed");
        byte[] saltedPassword = ScramEngine.computeSaltedPassword(pwd, parsed.salt, iterationCount);
        try {
            this.clientKey = ScramEngine.computeClientKey(saltedPassword);
            this.storedKey = ScramEngine.computeStoredKey(this.clientKey);
            this.serverKey = ScramEngine.computeServerKey(saltedPassword);
        } finally {
            // Zero SaltedPassword immediately after deriving keys (RFC 7804 §8)
            ScramEngine.zeroBytes(saltedPassword);
            // Zero password in memory
            this.password = null;
        }

        this.state = ScramState.SERVER_FIRST_RECEIVED;
    }

    /**
     * Compute the client-final-message with proof.
     *
     * @return the full client-final-message string
     */
    public String computeClientFinal() {
        String fullNonce = requireNonNull(serverNonce, "serverNonce not set");
        String serverFirst = requireNonNull(serverFirstMessage, "serverFirstMessage not set");
        byte[] currentStoredKey = requireNonNull(storedKey, "storedKey not set");
        byte[] currentClientKey = requireNonNull(clientKey, "clientKey not set");

        this.clientFinalMessageWithoutProof = ScramMessageFormatter.clientFinalMessageWithoutProof(fullNonce);

        // AuthMessage = client-first-message-bare + "," + server-first-message + "," + client-final-message-without-proof
        String authMessage = clientFirstMessageBare + "," + serverFirst + "," + clientFinalMessageWithoutProof;

        byte[] clientSignature = ScramEngine.computeClientSignature(currentStoredKey, authMessage);
        byte[] clientProof = ScramEngine.computeClientProof(currentClientKey, clientSignature);

        String clientFinal = ScramMessageFormatter.formatClientFinalMessage(fullNonce, clientProof);
        this.state = ScramState.CLIENT_FINAL_SENT;
        return clientFinal;
    }

    /**
     * Verify the server-final-message (ServerSignature).
     *
     * @param serverFinalMsg the decoded server-final-message
     * @return true if ServerSignature is valid, false otherwise
     */
    public boolean verifyServerFinal(String serverFinalMsg) {
        ScramMessageParser.ServerFinalMessage parsed = ScramMessageParser.parseServerFinal(serverFinalMsg);

        if (parsed.error != null) {
            this.state = ScramState.FAILED;
            return false;
        }

        if (parsed.verifier == null) {
            this.state = ScramState.FAILED;
            return false;
        }

        String serverFirst = requireNonNull(serverFirstMessage, "serverFirstMessage not set");
        String clientFinalNoProof = requireNonNull(clientFinalMessageWithoutProof, "clientFinalMessageWithoutProof not set");
        byte[] currentServerKey = requireNonNull(serverKey, "serverKey not set");

        // Reconstruct AuthMessage
        String authMessage = clientFirstMessageBare + "," + serverFirst + "," + clientFinalNoProof;

        byte[] expectedServerSignature = ScramEngine.computeServerSignature(currentServerKey, authMessage);
        byte[] receivedSignature;
        try {
            receivedSignature = Base64.getDecoder().decode(parsed.verifier);
        } catch (IllegalArgumentException e) {
            this.state = ScramState.FAILED;
            return false;
        }

        // Constant-time comparison to prevent timing side-channel attacks
        if (MessageDigest.isEqual(expectedServerSignature, receivedSignature)) {
            this.state = ScramState.AUTHENTICATED;
            return true;
        } else {
            this.state = ScramState.FAILED;
            return false;
        }
    }

    /**
     * Create a session cache entry from the current context after successful authentication.
     */
    public ScramSessionCache.Entry toSessionCacheEntry(@Nullable String serverNoncePart, int ttl) {
        byte[] currentSalt = requireNonNull(salt, "salt not set");
        byte[] currentClientKey = requireNonNull(clientKey, "clientKey not set");
        byte[] currentStoredKey = requireNonNull(storedKey, "storedKey not set");
        byte[] currentServerKey = requireNonNull(serverKey, "serverKey not set");
        String serverFirst = requireNonNull(serverFirstMessage, "serverFirstMessage not set");

        return new ScramSessionCache.Entry(
                realmName,
                currentSalt,
                iterationCount,
                currentClientKey,
                currentStoredKey,
                currentServerKey,
                serverNoncePart,
                ttl,
                System.nanoTime(),
                iterationCount, // nonce-count starts at iteration count per RFC 7804 §5.1
                serverFirst
        );
    }

    // Getters and setters

    public ScramState getState() {
        return state;
    }

    public void setState(ScramState state) {
        this.state = state;
    }

    public String getMechanism() {
        return mechanism;
    }

    public String getUsername() {
        return username;
    }

    public @Nullable String getRealmName() {
        return realmName;
    }

    public String getClientNonce() {
        return clientNonce;
    }

    public @Nullable String getServerNonce() {
        return serverNonce;
    }

    public @Nullable String getSid() {
        return sid;
    }

    public void setSid(@Nullable String sid) {
        this.sid = sid;
    }

    public String getClientFirstMessage() {
        return clientFirstMessage;
    }

    public String getClientFirstMessageBare() {
        return clientFirstMessageBare;
    }

    public @Nullable String getServerFirstMessage() {
        return serverFirstMessage;
    }

    public @Nullable byte[] getClientKey() {
        return clientKey != null ? clientKey.clone() : null;
    }

    public @Nullable byte[] getStoredKey() {
        return storedKey != null ? storedKey.clone() : null;
    }

    public @Nullable byte[] getServerKey() {
        return serverKey != null ? serverKey.clone() : null;
    }

    public @Nullable ScramMessageParser.ScramChallengeParams getInitialChallengeParams() {
        return initialChallengeParams;
    }

    public void setInitialChallengeParams(@Nullable ScramMessageParser.ScramChallengeParams params) {
        this.initialChallengeParams = params;
    }

    public int getIterationCount() {
        return iterationCount;
    }

    public @Nullable byte[] getSalt() {
        return salt != null ? salt.clone() : null;
    }

    public @Nullable String getClientFinalMessageWithoutProof() {
        return clientFinalMessageWithoutProof;
    }
}
