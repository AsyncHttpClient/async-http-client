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
package org.asynchttpclient.handler;

import org.asynchttpclient.RedirectPolicy;
import org.asynchttpclient.uri.Uri;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when a redirect was refused by the client's {@link RedirectPolicy} rather than followed. The
 * redirected request was never sent.
 *
 * <p>Deliberately not an {@link java.io.IOException}, and that is load-bearing rather than stylistic: the
 * HTTP/1.1 and HTTP/2 handlers both gate their {@code IOExceptionFilter} replay on
 * {@code t instanceof IOException}, and the in-tree {@code ResumableIOExceptionFilter} replays on any
 * {@link java.io.IOException}, so an {@link java.io.IOException} here could turn one refusal into up to
 * {@link org.asynchttpclient.AsyncHttpClientConfig#getMaxRequestRetry()} further submissions of content the
 * caller asked to send once. It also keeps the refusal out of the message-substring recovery heuristic the
 * handlers apply to I/O failures, which would otherwise be fed server-influenced text.
 * {@link MaxRedirectException} is not an {@link java.io.IOException} for the same reason.
 *
 * <p>{@link #getStatusCode()} is what a caller needs to decide whether re-sending is safe. A 303, and a 301
 * or 302 that rewrote a {@code POST} to a {@code GET}, indicate that the origin has very likely already
 * applied the original request (RFC 9110 sections 15.4.2, 15.4.3 and 15.4.4). A 307, a 308 or a strict 302
 * preserved the method and the content, so the same request can be re-sent to a secured target - but the
 * status code alone does not prove the origin did not apply it, so a non-idempotent method still needs the
 * caller's judgement (RFC 9110 section 9.2.2).
 *
 * <p>Carries no stack trace: the frames would describe a Netty event loop, and the
 * {@link java.util.concurrent.ExecutionException} the caller unwraps already carries their own.
 */
public class RedirectRefusedException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    // Uri is not Serializable while Throwable is, so this cannot survive a round trip. getMessage() and
    // getStatusCode() carry the same facts and do survive.
    private final transient @Nullable Uri targetUri;

    /**
     * @param message    the refusal, naming both origins by base URL only
     * @param statusCode the status code of the redirect response that was refused
     * @param targetUri  the redirect target that was refused
     */
    public RedirectRefusedException(String message, int statusCode, Uri targetUri) {
        super(message, null, true, false);
        this.statusCode = statusCode;
        this.targetUri = targetUri;
    }

    /**
     * @return the status code of the redirect response that was refused
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * The refused target. Its path, query and userinfo may all be server-chosen and none of them belongs in a
     * log; {@link Uri#getBaseUrl()} is the loggable form, and is what {@link #getMessage()} already uses.
     *
     * @return the refused redirect target, or {@code null} on an instance that was deserialized
     */
    public @Nullable Uri getTargetUri() {
        return targetUri;
    }
}
