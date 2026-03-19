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

import java.util.Base64;

/**
 * Formats SCRAM protocol messages (RFC 5802) and HTTP headers (RFC 7804).
 * Thread-safe: all methods are stateless.
 */
public final class ScramMessageFormatter {

    private ScramMessageFormatter() {
    }

    /**
     * Escape a username per RFC 5802: "=" → "=3D", "," → "=2C".
     */
    public static String escapeUsername(String username) {
        // Order matters: escape '=' first to avoid double-escaping
        return username.replace("=", "=3D").replace(",", "=2C");
    }

    /**
     * Format the bare portion of client-first-message (without gs2-header).
     * "n=&lt;escaped-user&gt;,r=&lt;c-nonce&gt;"
     */
    public static String clientFirstMessageBare(String username, String clientNonce) {
        return "n=" + escapeUsername(username) + ",r=" + clientNonce;
    }

    /**
     * Format the full client-first-message including gs2-header.
     * "n,,n=&lt;escaped-user&gt;,r=&lt;c-nonce&gt;"
     */
    public static String formatClientFirstMessage(String username, String clientNonce) {
        return "n,," + clientFirstMessageBare(username, clientNonce);
    }

    /**
     * Format client-final-message-without-proof.
     * "c=biws,r=&lt;full-nonce&gt;"
     */
    public static String clientFinalMessageWithoutProof(String fullNonce) {
        return "c=biws,r=" + fullNonce;
    }

    /**
     * Format the full client-final-message with proof.
     * "c=biws,r=&lt;full-nonce&gt;,p=&lt;base64-proof&gt;"
     */
    public static String formatClientFinalMessage(String fullNonce, byte[] clientProof) {
        return clientFinalMessageWithoutProof(fullNonce) + ",p=" + Base64.getEncoder().encodeToString(clientProof);
    }

    /**
     * Format the HTTP Authorization header value for SCRAM.
     * Per Erratum 6558, the data attribute is quoted.
     *
     * @param mechanism  "SCRAM-SHA-256"
     * @param realm      realm value (may be null)
     * @param sid        session ID (may be null)
     * @param base64Data base64-encoded SCRAM message
     * @return formatted header value
     */
    public static String formatAuthorizationHeader(String mechanism, @Nullable String realm,
                                                    @Nullable String sid, String base64Data) {
        StringBuilder sb = new StringBuilder(mechanism);
        if (realm != null) {
            sb.append(" realm=\"").append(realm).append("\",");
        }
        if (sid != null) {
            sb.append(" sid=").append(sid).append(",");
        }
        sb.append(" data=\"").append(base64Data).append("\"");
        return sb.toString();
    }
}
