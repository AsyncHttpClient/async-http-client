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
 * Extra restrictions on a redirect the client would otherwise follow. Needs
 * {@link AsyncHttpClientConfig#isFollowRedirect()}, and is checked once per hop against the URI that hop is
 * leaving - so {@code http -> https -> http} is refused at the second hop, not judged against the cleartext
 * start.
 *
 * <p>Every constant is subtractive: it can stop a hop, never allow one, and never relaxes the credential
 * stripping on {@link AsyncHttpClientConfig#isStripAuthorizationOnRedirect()}. They are not an ordered
 * ladder - each carries its restrictions as explicit flags and they are resolved independently, so adding a
 * constant later cannot disturb an arm it does not carry.
 *
 * <p>A refused hop fails the future with {@link org.asynchttpclient.handler.RedirectRefusedException} rather
 * than handing back the 3xx, matching {@link org.asynchttpclient.handler.MaxRedirectException} and differing
 * from {@code java.net.http.HttpClient.Redirect#NORMAL}. To see the 3xx instead, use {@link #ALLOW_ALL},
 * turn redirects off, or install a {@code ResponseFilter}, which runs first.
 * {@link AsyncHttpClientConfig#getMaxRedirects()} is also checked first, so an over-budget hop reports
 * {@code MaxRedirectException} even if it was refusable.
 *
 * @see AsyncHttpClientConfig#getRedirectPolicy()
 * @see Request#getRedirectPolicy()
 */
public enum RedirectPolicy {

    /**
     * Follow every redirect the server sends. The historical behaviour, and the default.
     */
    ALLOW_ALL(false, false),

    /**
     * Also refuse a hop leaving a secured scheme ({@code https} or {@code wss}) for one that is not,
     * whatever the method and whether or not there is content: the target URI and query, the non-{@code Secure}
     * cookies the store holds for that host, and the whole response all go out in the clear (CWE-319).
     * RFC 9110 section 4.2.2 asks that extensions applying across all origins with the same host - the
     * Cookie protocol being its example - "be designed with great care to prevent information obtained from a
     * secured connection being inadvertently exchanged within an unsecured context".
     *
     * <p>An upgrade cannot satisfy the predicate, so it is always followed, including to another host. This
     * refuses at least what {@code java.net.http.HttpClient.Redirect#NORMAL} does and a little more: it tests
     * {@code Uri.isSecured()}, not the {@code https} scheme, so {@code wss} to {@code ws} counts too.
     */
    REFUSE_INSECURE_DOWNGRADE(true, false),

    /**
     * Everything above, plus a hop that would replay the request content to a different origin - scheme, host
     * or effective port (RFC 6454 sections 4 and 5). Stripping credentials does nothing for what is inside
     * the content (CWE-201).
     *
     * <p>Only fires when content would really be sent again, so a bodiless request is unaffected, and so is
     * one the redirect already strips: a {@code POST} a 301 or non-strict 302 turns into a {@code GET}, or
     * anything a 303 rewrites. Since {@code http} and {@code https} are distinct origins, a same-host upgrade
     * carrying content is refused too - address {@code https} directly.
     *
     * <p>Restricts the content only. A bodiless cross-origin hop still goes, still carrying caller-set
     * headers other than {@code Authorization}, {@code Proxy-Authorization} and {@code Cookie} - a custom
     * API-key header, {@code Referer}, {@code Origin}. No other widely used client does this, hence opt-in.
     */
    REFUSE_INSECURE_DOWNGRADE_AND_CROSS_ORIGIN_BODY(true, true);

    private final boolean refusesInsecureDowngrade;
    private final boolean refusesCrossOriginBodyReplay;

    RedirectPolicy(boolean refusesInsecureDowngrade, boolean refusesCrossOriginBodyReplay) {
        this.refusesInsecureDowngrade = refusesInsecureDowngrade;
        this.refusesCrossOriginBodyReplay = refusesCrossOriginBodyReplay;
    }

    /**
     * Whether this policy refuses a hop that leaves a secured scheme for an unsecured one.
     *
     * @return whether such a hop is refused
     */
    public boolean refusesInsecureDowngrade() {
        return refusesInsecureDowngrade;
    }

    /**
     * Whether this policy refuses a hop that would replay the request's content to a different origin.
     *
     * @return whether such a hop is refused
     */
    public boolean refusesCrossOriginBodyReplay() {
        return refusesCrossOriginBodyReplay;
    }
}
