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

import org.asynchttpclient.Realm;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

/**
 * Parser for SCRAM protocol messages (RFC 5802) and HTTP authentication headers (RFC 7804).
 * Thread-safe: all methods are stateless.
 */
public final class ScramMessageParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScramMessageParser.class);

    private ScramMessageParser() {
    }

    /**
     * Parsed server-first-message fields.
     */
    public static class ServerFirstMessage {
        public final String fullNonce;   // r= value (client-nonce + server-nonce)
        public final byte[] salt;        // s= value (base64-decoded)
        public final int iterationCount; // i= value

        public ServerFirstMessage(String fullNonce, byte[] salt, int iterationCount) {
            this.fullNonce = fullNonce;
            this.salt = salt;
            this.iterationCount = iterationCount;
        }
    }

    /**
     * Parsed server-final-message fields.
     */
    public static class ServerFinalMessage {
        public final @Nullable String verifier; // v= value (base64 encoded ServerSignature)
        public final @Nullable String error;    // e= value

        public ServerFinalMessage(@Nullable String verifier, @Nullable String error) {
            this.verifier = verifier;
            this.error = error;
        }
    }

    /**
     * Parsed SCRAM HTTP challenge/response parameters from WWW-Authenticate header.
     */
    public static class ScramChallengeParams {
        public final @Nullable String realm;
        public final @Nullable String data;  // base64 encoded SCRAM message
        public final @Nullable String sid;
        public final @Nullable String sr;    // server nonce for reauthentication
        public final int ttl;                // -1 if absent
        public final boolean stale;

        public ScramChallengeParams(@Nullable String realm, @Nullable String data,
                                    @Nullable String sid, @Nullable String sr,
                                    int ttl, boolean stale) {
            this.realm = realm;
            this.data = data;
            this.sid = sid;
            this.sr = sr;
            this.ttl = ttl;
            this.stale = stale;
        }
    }

    /**
     * Parse a server-first-message (RFC 5802): {@code r=<nonce>,s=<salt>,i=<count>[,extensions]}
     */
    public static ServerFirstMessage parseServerFirst(String message) {
        String fullNonce = null;
        String saltBase64 = null;
        int iterationCount = -1;

        String[] parts = message.split(",");
        for (String part : parts) {
            if (part.startsWith("r=")) {
                fullNonce = part.substring(2);
            } else if (part.startsWith("s=")) {
                saltBase64 = part.substring(2);
            } else if (part.startsWith("i=")) {
                try {
                    iterationCount = Integer.parseInt(part.substring(2));
                } catch (NumberFormatException e) {
                    throw new ScramException("Invalid iteration count in server-first-message: " + part.substring(2));
                }
            }
            // Extensions after i= are tolerated per RFC 5802
        }

        if (fullNonce == null) {
            throw new ScramException("Missing nonce (r=) in server-first-message");
        }
        if (saltBase64 == null || saltBase64.isEmpty()) {
            throw new ScramException("Missing or empty salt (s=) in server-first-message");
        }

        byte[] salt;
        try {
            salt = Base64.getDecoder().decode(saltBase64);
        } catch (IllegalArgumentException e) {
            throw new ScramException("Invalid base64 salt in server-first-message", e);
        }
        if (salt.length == 0) {
            throw new ScramException("Empty salt in server-first-message");
        }

        if (iterationCount < 1) {
            throw new ScramException("Invalid iteration count: " + iterationCount);
        }

        return new ServerFirstMessage(fullNonce, salt, iterationCount);
    }

    /**
     * Parse a server-final-message (RFC 5802): {@code v=<verifier>} OR {@code e=<error>}
     */
    public static ServerFinalMessage parseServerFinal(String message) {
        if (message.startsWith("v=")) {
            return new ServerFinalMessage(message.substring(2), null);
        } else if (message.startsWith("e=")) {
            return new ServerFinalMessage(null, message.substring(2));
        } else {
            throw new ScramException("Invalid server-final-message: must start with v= or e=");
        }
    }

    /**
     * Parse SCRAM-specific parameters from a WWW-Authenticate or Authentication-Info header value.
     * The header value should have the SCRAM-SHA-xxx prefix already stripped (or the full header).
     */
    public static ScramChallengeParams parseWwwAuthenticateScram(String headerValue) {
        String realm = Realm.Builder.matchParam(headerValue, "realm");
        String data = Realm.Builder.matchParam(headerValue, "data");
        String sid = Realm.Builder.matchParam(headerValue, "sid");
        if (sid == null) {
            // sid may be unquoted token
            sid = matchUnquotedToken(headerValue, "sid");
        }
        String sr = Realm.Builder.matchParam(headerValue, "sr");
        if (sr == null) {
            sr = matchUnquotedToken(headerValue, "sr");
        }

        int ttl = -1;
        String ttlStr = Realm.Builder.matchParam(headerValue, "ttl");
        if (ttlStr == null) {
            ttlStr = matchUnquotedToken(headerValue, "ttl");
        }
        if (ttlStr != null) {
            try {
                ttl = Integer.parseInt(ttlStr);
            } catch (NumberFormatException e) {
                LOGGER.warn("SCRAM: invalid ttl value: {}", ttlStr);
            }
        }

        String staleStr = Realm.Builder.matchParam(headerValue, "stale");
        if (staleStr == null) {
            staleStr = matchUnquotedToken(headerValue, "stale");
        }
        boolean stale = "true".equalsIgnoreCase(staleStr);

        // Check for duplicate realm (RFC 7804 §5: MUST NOT appear more than once)
        checkDuplicateRealm(headerValue);

        return new ScramChallengeParams(realm, data, sid, sr, ttl, stale);
    }

    /**
     * Match an unquoted token value in a header (e.g., sid=AAAABBBB).
     */
    private static @Nullable String matchUnquotedToken(String headerLine, String token) {
        String prefix = token + "=";
        int idx = headerLine.indexOf(prefix);
        while (idx >= 0) {
            // Verify the character before is a boundary
            if (idx == 0 || headerLine.charAt(idx - 1) == ' ' || headerLine.charAt(idx - 1) == ',') {
                int valStart = idx + prefix.length();
                if (valStart < headerLine.length() && headerLine.charAt(valStart) != '"') {
                    int valEnd = valStart;
                    while (valEnd < headerLine.length() && headerLine.charAt(valEnd) != ',' && headerLine.charAt(valEnd) != ' ') {
                        valEnd++;
                    }
                    if (valEnd > valStart) {
                        return headerLine.substring(valStart, valEnd);
                    }
                }
            }
            idx = headerLine.indexOf(prefix, idx + 1);
        }
        return null;
    }

    private static void checkDuplicateRealm(String headerValue) {
        int first = indexOfParam(headerValue, "realm");
        if (first >= 0) {
            int second = indexOfParam(headerValue, "realm", first + 1);
            if (second >= 0) {
                LOGGER.warn("SCRAM: duplicate realm attribute detected (RFC 7804 §5: MUST NOT appear more than once)");
            }
        }
    }

    private static int indexOfParam(String headerValue, String param) {
        return indexOfParam(headerValue, param, 0);
    }

    private static int indexOfParam(String headerValue, String param, int fromIndex) {
        String search = param + "=";
        int idx = headerValue.indexOf(search, fromIndex);
        while (idx >= 0) {
            if (idx == 0 || headerValue.charAt(idx - 1) == ' ' || headerValue.charAt(idx - 1) == ',') {
                return idx;
            }
            idx = headerValue.indexOf(search, idx + 1);
        }
        return -1;
    }

    /**
     * Validate that the full nonce starts with the client nonce.
     */
    public static void validateNoncePrefix(String clientNonce, String fullNonce) {
        if (!fullNonce.startsWith(clientNonce)) {
            throw new ScramException("Server nonce does not contain client nonce prefix");
        }
        if (fullNonce.length() <= clientNonce.length()) {
            throw new ScramException("Server nonce part is empty");
        }
    }

    /**
     * Validate the gs2-header starts with "n" (no channel binding).
     */
    public static void validateGs2Header(String message) {
        if (!message.startsWith("n,")) {
            throw new ScramException("Invalid gs2-header: channel binding not supported, must start with 'n'");
        }
    }
}
