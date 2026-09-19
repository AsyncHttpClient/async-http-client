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

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.uri.Uri;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when a redirect was refused rather than followed, by
 * {@link AsyncHttpClientConfig#isRefuseSchemeDowngradeOnRedirect()} or
 * {@link AsyncHttpClientConfig#isRefuseCrossOriginBodyOnRedirect()}. The redirected request was never sent.
 *
 * <p>When the origin answers the 3xx before the request content has finished writing, the failed write can
 * surface first and the exchange fails with that {@link java.io.IOException} instead. The content still never
 * reaches the new origin, but a caller branching on this type alone will miss that case.
 *
 * <p>Deliberately not an {@link java.io.IOException}: both handlers gate their {@code IOExceptionFilter}
 * replay on that type, so a refusal that was one would come back as several more attempts to send the content
 * the caller asked to send once. {@link MaxRedirectException} avoids it for the same reason.
 *
 * <p>Whether re-sending by hand is safe depends on {@link #getStatusCode()}. Every refusal except a 303, and
 * except a 301 or non-strict 302 on a {@code POST}, preserved both the method and the content, so the same
 * request can be aimed somewhere else unchanged. A 303 is defined as an indirect response to the original
 * request, typically the output of a {@code POST} that has already run, so the origin has very likely applied
 * it (RFC 9110 section 15.4.4). No status proves it did not, though, so a non-idempotent method still
 * needs the caller's judgement (section 9.2.2).
 */
public class RedirectRefusedException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    // Uri is not Serializable. getMessage() and getStatusCode() carry the same facts and do survive.
    private final transient @Nullable Uri targetUri;

    /**
     * @param message    the refusal, naming both origins by base URL only
     * @param statusCode the status code of the redirect response that was refused
     * @param targetUri  the redirect target that was refused
     */
    public RedirectRefusedException(String message, int statusCode, Uri targetUri) {
        // No stack trace: the frames would all be Netty's event loop, and the caller's ExecutionException
        // already carries theirs.
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
     * The refused target. Its path, query and userinfo are server-chosen, so prefer {@link Uri#getBaseUrl()}
     * when logging it, as {@link #getMessage()} already does.
     *
     * @return the refused redirect target, or null on an instance that was deserialized
     */
    public @Nullable Uri getTargetUri() {
        return targetUri;
    }
}
