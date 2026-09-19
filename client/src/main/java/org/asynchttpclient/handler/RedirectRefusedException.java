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
 * Thrown when {@link RedirectPolicy} refused a redirect instead of following it. The redirected request was
 * never sent.
 *
 * <p>Not an {@link java.io.IOException}, and that is load-bearing: the HTTP/1.1 and HTTP/2 handlers both gate
 * their {@code IOExceptionFilter} replay on that type, and the in-tree {@code ResumableIOExceptionFilter}
 * replays on any of them, so one refusal would become several more submissions of content the caller asked to
 * send once. {@link MaxRedirectException} avoids {@code IOException} for the same reason.
 *
 * <p>{@link #getStatusCode()} is what says whether re-sending is safe. A 303 means the origin has very likely
 * already applied the request - it is primarily used to hand back the output of a {@code POST} as its own
 * resource (RFC 9110 section 15.4.4). A 301 or 302 that rewrote a {@code POST} to a {@code GET} says nothing either
 * way: both describe a relocation (sections 15.4.2 and 15.4.3) and the rewrite is a historical user-agent
 * allowance (section 15.4). A 307, 308 or strict 302 kept the method and content, so the same request can go
 * to a secured target - though no status proves the origin did not apply it, so a non-idempotent method still
 * needs judgement (section 9.2.2).
 *
 * <p>Carries no stack trace: the frames would be a Netty event loop, and the caller's
 * {@link java.util.concurrent.ExecutionException} already has theirs.
 */
public class RedirectRefusedException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    // Uri is not Serializable; getMessage() and getStatusCode() carry the same facts and do survive.
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
     * The refused target. Its path, query and userinfo are server-chosen, so log {@link Uri#getBaseUrl()}
     * rather than this - which is what {@link #getMessage()} already does.
     *
     * @return the refused redirect target, or {@code null} on an instance that was deserialized
     */
    public @Nullable Uri getTargetUri() {
        return targetUri;
    }
}
