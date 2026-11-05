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
package org.asynchttpclient.netty.handler.intercept;

import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.uri.Uri;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.COOKIE;
import static io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE;
import static org.asynchttpclient.util.HttpUtils.cookiePairName;
import static org.asynchttpclient.util.HttpUtils.cookiePairs;

/**
 * Tells the cookies a caller put on a request from the ones the cookie store added, for the request a redirect
 * or an authentication retry builds from it. The store's may be older than what the response just set.
 */
final class CallerCookies {

    private CallerCookies() {
    }

    /**
     * The request's cookie objects minus the ones the store put there: those this response set, rotated or
     * deleted, and those the store still holds with the same value. A caller's cookie sharing only a name with
     * a stored one stays the caller's. A Cookie header the caller set is not among these; see
     * {@link #headerCookies}.
     */
    private static List<Cookie> of(List<Cookie> cookies, List<Cookie> stored, Set<String> setByResponse) {
        if (cookies.isEmpty()) {
            return cookies;
        }
        List<Cookie> callers = new ArrayList<>(cookies.size());
        for (Cookie cookie : cookies) {
            if (!setByResponse.contains(cookie.name().toLowerCase(Locale.ROOT)) && !holdsSameValue(stored, cookie)) {
                callers.add(cookie);
            }
        }
        return callers;
    }

    private static Set<String> replacedNames(HttpResponse response, List<Cookie> nextCookies, Uri current, Uri next,
                                             ClientCookieDecoder cookieDecoder) {
        // A Set-Cookie replaces the caller's cookie of that name only where its Domain and Path apply, matched as
        // the store matches them, and one that does not delete only if the store now holds its value for the next
        // request. The store's other checks are not repeated: a deletion it refuses for another reason, such as a
        // public suffix as Domain, still counts. Names folded as the store keys them, so a SID set here also
        // replaces a request's sid.
        Set<String> setByResponse = new HashSet<>();
        for (String header : response.headers().getAll(SET_COOKIE)) {
            Cookie cookie;
            try {
                cookie = cookieDecoder.decode(header);
            } catch (IllegalArgumentException e) {
                // Interceptors dropped it too, so the store still holds the old value and holdsSameValue decides.
                continue;
            }
            if (cookie == null) {
                continue;
            }
            String name = cookie.name().toLowerCase(Locale.ROOT);
            if (cookie.maxAge() != Cookie.UNDEFINED_MAX_AGE && cookie.maxAge() <= 0) {
                // A deletion also counts if it applies only to the request it answered. The cookie it deleted
                // there was sent with that request: once gone from the store, it can no longer be told from
                // the caller's, and a caller's pair may be a copy of it.
                if (appliesTo(cookie, current, current) || appliesTo(cookie, current, next)) {
                    setByResponse.add(name);
                }
                continue;
            }
            if (appliesTo(cookie, current, next) && holdsSameValue(nextCookies, cookie)) {
                setByResponse.add(name);
            }
        }
        return setByResponse;
    }

    /**
     * For a retry of the same request: the caller's cookies, then the store's current ones.
     */
    static void refresh(RequestBuilder retry, Request request, HttpResponse response, CookieStore cookieStore,
                        ClientCookieDecoder cookieDecoder) {
        refresh(retry, request, response, cookieStore, request.getUri(), cookieDecoder);
    }

    static void refresh(RequestBuilder retry, Request request, HttpResponse response, CookieStore cookieStore,
                        Uri next, ClientCookieDecoder cookieDecoder) {
        List<Cookie> nextCookies = cookieStore.get(next);
        Set<String> setByResponse = replacedNames(response, nextCookies, request.getUri(), next, cookieDecoder);
        List<Cookie> cookies = request.getCookies();
        // A retry goes to the same URI again, so one lookup serves both.
        List<Cookie> stored = cookies.isEmpty() || next.equals(request.getUri())
                ? nextCookies : cookieStore.get(request.getUri());
        retry.setCookies(of(cookies, stored, setByResponse));
        retry.setHeader(COOKIE, headerCookies(request.getHeaders().getAll(COOKIE), setByResponse));
        for (Cookie cookie : nextCookies) {
            retry.addCookieIfUnset(cookie);
        }
    }

    /**
     * The caller's Cookie header values minus the pairs this response replaced, kept as text: a lenient decode
     * followed by strict encoding can reject a value that was sent on the first attempt.
     * <p>
     * The pairs come from {@code HttpUtils.cookiePairs}, as in {@code NettyRequestFactory#mergeCookies} when it
     * decides which names the caller set, so every ';' ends one. Honouring quotes here would let a stray '"' hide
     * a replaced pair in its neighbour's value, to be resent stale while the merge still counts its name as the
     * caller's and drops the store's new value. Unlike the merge, names are folded as the store folds them, so
     * replacing SID also cuts a pair named sid. Only the replaced pairs are cut out, so the rest keeps its bytes
     * and a header that loses none is returned as is.
     */
    private static List<String> headerCookies(List<String> headers, Set<String> replaced) {
        if (replaced.isEmpty() || headers.isEmpty()) {
            return headers;
        }
        List<String> remaining = new ArrayList<>(headers.size());
        for (String header : headers) {
            String[] pairs = cookiePairs(header);
            if (!anyReplaced(pairs, replaced)) {
                remaining.add(header);
                continue;
            }
            StringBuilder kept = new StringBuilder(header.length());
            for (String pair : pairs) {
                if (!isReplaced(pair, replaced)) {
                    if (kept.length() > 0) {
                        kept.append(';');
                    }
                    kept.append(pair);
                }
            }
            // Cutting the first or last pair leaves the whitespace or ';' that separated it at that end.
            int from = 0;
            int to = kept.length();
            while (from < to && (kept.charAt(from) == ';' || kept.charAt(from) <= ' ')) {
                from++;
            }
            while (to > from && (kept.charAt(to - 1) == ';' || kept.charAt(to - 1) <= ' ')) {
                to--;
            }
            if (from < to) {
                remaining.add(kept.substring(from, to));
            }
        }
        return remaining;
    }

    private static boolean anyReplaced(String[] pairs, Set<String> replaced) {
        for (String pair : pairs) {
            if (isReplaced(pair, replaced)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReplaced(String pair, Set<String> replaced) {
        String name = cookiePairName(pair);
        return !name.isEmpty() && replaced.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether the Domain and Path of a cookie in the response to {@code answered} match {@code uri}, as the store
     * matches them (RFC 6265 sections 5.1.3 and 5.1.4). Only for a {@code uri} on the same host, the only kind a
     * retry or a redirect that keeps the caller's cookies goes to.
     */
    private static boolean appliesTo(Cookie cookie, Uri answered, Uri uri) {
        String domain = cookie.domain() == null ? "" : canonicalHost(cookie.domain());
        if (domain.startsWith(".")) {
            domain = domain.substring(1);
        }
        if (!domain.isEmpty()) {
            String host = canonicalHost(uri.getHost());
            if (!host.equals(domain) && !host.endsWith('.' + domain)) {
                return false;
            }
        }
        String path = cookie.path();
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            String answeredPath = answered.getPath();
            int lastSlash = answeredPath.lastIndexOf('/');
            path = answeredPath.startsWith("/") && lastSlash > 0 ? answeredPath.substring(0, lastSlash) : "/";
        }
        String uriPath = uri.getPath().isEmpty() ? "/" : uri.getPath();
        return uriPath.startsWith(path) && (uriPath.length() == path.length()
                || path.charAt(path.length() - 1) == '/' || uriPath.charAt(path.length()) == '/');
    }

    private static String canonicalHost(String host) {
        String lowered = host.toLowerCase(Locale.ROOT);
        int end = lowered.length();
        while (end > 0 && lowered.charAt(end - 1) == '.') {
            end--;
        }
        return lowered.substring(0, end);
    }

    private static boolean holdsSameValue(List<Cookie> stored, Cookie cookie) {
        for (Cookie candidate : stored) {
            if (candidate.name().equals(cookie.name()) && candidate.value().equals(cookie.value())) {
                return true;
            }
        }
        return false;
    }
}
