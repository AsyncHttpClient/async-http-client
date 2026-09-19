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
package org.asynchttpclient;

/**
 * Additional restrictions on a redirect the client would otherwise follow.
 *
 * <p>Has no effect unless {@link AsyncHttpClientConfig#isFollowRedirect()} is enabled, and is evaluated once
 * per hop against the URI that hop is leaving, not against the URI the exchange started from. So in an
 * {@code http -> https -> http} chain, {@link #REFUSE_INSECURE_DOWNGRADE} refuses the second hop - the one
 * leaving {@code https} - even though the exchange itself began in cleartext.
 *
 * <p>Every constant is <em>subtractive</em>: it can only stop a hop that would be followed today, never allow
 * one that is refused today, and never relax the credential stripping described on
 * {@link AsyncHttpClientConfig#isStripAuthorizationOnRedirect()}. {@link #ALLOW_ALL} restores the historical
 * hop decision and restores nothing about credentials.
 *
 * <p>The constants are <strong>not an ordered ladder</strong> and nothing compares them. Each declares the
 * restrictions it carries as explicit flags, and the two restrictions are resolved independently, so a
 * constant added later that carries one and not the other cannot affect the other.
 *
 * <p>A refused hop fails the exchange's future with
 * {@link org.asynchttpclient.handler.RedirectRefusedException}; the redirect response is not delivered as a
 * 3xx. That differs from {@code java.net.http.HttpClient.Redirect#NORMAL}, which hands the 3xx back, and
 * follows this client's own precedent for declining a hop the caller enabled
 * ({@link org.asynchttpclient.handler.MaxRedirectException}). A caller who wants to inspect the 3xx instead
 * can use {@link #ALLOW_ALL}, disable redirects, or install a {@code ResponseFilter}, which runs before the
 * redirect is dispatched.
 *
 * <p>{@link AsyncHttpClientConfig#getMaxRedirects()} is checked first, so a hop that is both over budget and
 * refusable is reported as a {@link org.asynchttpclient.handler.MaxRedirectException}.
 *
 * @see AsyncHttpClientConfig#getRedirectPolicy()
 * @see Request#getRedirectPolicy()
 */
public enum RedirectPolicy {

    /**
     * Follow every redirect the server sends. This is the historical behaviour and the default.
     */
    ALLOW_ALL(false, false),

    /**
     * Also refuse a hop whose origin scheme is secured ({@code https} or {@code wss}) and whose target scheme
     * is not, whatever the method and whether or not the request carries content.
     *
     * <p>It is body-independent because the body is not the only thing exposed: the target URI and its query
     * string, every cookie the cookie store holds for the target host without the {@code Secure} attribute,
     * and the whole response all cross the network in the clear (CWE-319). RFC 9110 section 4.2.2 requires a
     * client to ensure its requests for an {@code https} resource are secured before they are communicated.
     *
     * <p>A hop in the other direction is an upgrade and is always followed, including to another host: the
     * predicate reads the two schemes only, so an upgrade cannot satisfy it. Equivalent in effect to
     * {@code java.net.http.HttpClient.Redirect#NORMAL}.
     */
    REFUSE_INSECURE_DOWNGRADE(true, false),

    /**
     * Everything {@link #REFUSE_INSECURE_DOWNGRADE} refuses, plus a hop that would replay the request's
     * content to a different origin, that is one whose scheme, host or effective port differs (RFC 6454
     * section 4). Stripping credentials does not protect information held in the content itself (CWE-201).
     *
     * <p>Fires only when content would actually be sent again, so a request carrying no content is
     * unaffected, and so is one whose content the redirect already drops: a {@code POST} that a 301 or a
     * non-strict 302 rewrites to a bodiless {@code GET}, and any method a 303 rewrites.
     *
     * <p>RFC 9110 section 4.2.2 makes {@code http} and {@code https} distinct origins, so a same-host
     * {@code http} to {@code https} hop that carries content is refused too; address {@code https} directly
     * instead. Note that such a request has already been sent once in the clear.
     *
     * <p>Only the request <em>content</em> is restricted. A bodiless cross-origin hop is still followed, and
     * still forwards caller-set headers other than {@code Authorization}, {@code Proxy-Authorization} and
     * {@code Cookie} - including a custom API-key header, {@code Referer} and {@code Origin}. No other widely
     * used HTTP client restricts cross-origin content replay, so this is opt-in.
     */
    REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY(true, true);

    private final boolean refusesInsecureDowngrade;
    private final boolean refusesCrossOriginBodyReplay;

    RedirectPolicy(boolean refusesInsecureDowngrade, boolean refusesCrossOriginBodyReplay) {
        this.refusesInsecureDowngrade = refusesInsecureDowngrade;
        this.refusesCrossOriginBodyReplay = refusesCrossOriginBodyReplay;
    }

    /**
     * @return whether a hop leaving a secured scheme for an unsecured one is refused
     */
    public boolean refusesInsecureDowngrade() {
        return refusesInsecureDowngrade;
    }

    /**
     * @return whether a hop that would replay the request's content to a different origin is refused
     */
    public boolean refusesCrossOriginBodyReplay() {
        return refusesCrossOriginBodyReplay;
    }
}
