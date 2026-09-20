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
 * <p>Whether re-sending by hand is safe depends on {@link #getStatusCode()}. Every refusal except a 303,
 * and except a 301 or non-strict 302 on a {@code POST}, preserved both the method and the content, so the
 * same request can be aimed somewhere else unchanged. A 303 is intended as an indirect response to a
 * request the origin has very likely already applied (RFC 9110 section 15.4.4), and no status proves
 * otherwise, so a non-idempotent method still needs the caller's judgement (section 9.2.2).
 */
public final class RedirectRefusedException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Which gate refused the redirect. Constants are only ever appended, so a caller switching on this must
     * still handle a value it does not know.
     */
    public enum Reason {

        /** The hop left a secured scheme for one that is not. */
        SCHEME_DOWNGRADE("the target scheme is not secured"),

        /** The hop would have resent the request content to an origin the caller never addressed. */
        CROSS_ORIGIN_BODY("it would resend the request content to another origin");

        // A field, not a switch: a constant added later cannot compile without a description.
        private final String description;

        Reason(String description) {
            this.description = description;
        }
    }

    private final Reason reason;
    private final int statusCode;
    // Uri is not Serializable. getMessage(), getReason() and getStatusCode() do survive.
    private final transient @Nullable Uri sourceUri;
    private final transient @Nullable Uri targetUri;

    /**
     * @param reason     which gate refused the redirect
     * @param statusCode the status code of the redirect response that was refused
     * @param sourceUri  the URI this hop was leaving
     * @param targetUri  the redirect target that was refused
     */
    public RedirectRefusedException(Reason reason, int statusCode, Uri sourceUri, Uri targetUri) {
        // No stack trace: the frames would all be Netty's event loop, and the caller's ExecutionException
        // already carries theirs.
        super(message(reason, statusCode, sourceUri, targetUri), null, true, false);
        this.reason = reason;
        this.statusCode = statusCode;
        this.sourceUri = sourceUri;
        this.targetUri = targetUri;
    }

    // getBaseUrl() on both sides: toBaseUrl() keeps the path and toString() keeps userinfo, neither of which
    // belongs in a log. Built here rather than by the caller so a second refusal site cannot reintroduce
    // that, and eagerly rather than in getMessage() so it survives serialization.
    private static String message(Reason reason, int statusCode, Uri sourceUri, Uri targetUri) {
        return "Refusing to follow the " + statusCode + " redirect from " + sourceUri.getBaseUrl()
                + " to " + targetUri.getBaseUrl() + ": " + reason.description;
    }

    /**
     * @return which gate refused the redirect
     */
    public Reason getReason() {
        return reason;
    }

    /**
     * @return the status code of the redirect response that was refused
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * The URI this hop was leaving, which is the request actually sent on this leg rather than the one the
     * caller built. Prefer {@link Uri#getBaseUrl()} when logging it, as {@link #getMessage()} already does.
     *
     * @return the URI the refused redirect was leaving, or null on an instance that was deserialized
     */
    public @Nullable Uri getSourceUri() {
        return sourceUri;
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
