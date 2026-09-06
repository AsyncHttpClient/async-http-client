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
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.uri.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.COOKIE;
import static io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE;

/**
 * Tells the cookies a caller put on a request from the ones the cookie store added, for the request a redirect
 * or an authentication retry builds from it. The store's may be older than what the response just set.
 */
final class CallerCookies {

    private CallerCookies() {
    }

    /**
     * The request's cookies minus the ones the store put there: those this response set, rotated or deleted,
     * and those the store still holds with the same value. A caller's cookie sharing only a name with a stored
     * one stays the caller's.
     */
    static List<Cookie> of(Request request, HttpResponse response, CookieStore cookieStore, Uri next,
                           ClientCookieDecoder cookieDecoder) {
        List<Cookie> cookies = withHeaderCookies(request);
        if (cookies.isEmpty()) {
            return cookies;
        }
        // Only what the store took counts: a Set-Cookie it refused, or one for another path, leaves the
        // caller's cookie of that name in place. Names folded as the store keys them, so a SID set here also
        // replaces a request's sid.
        Set<String> setByResponse = new HashSet<>();
        List<Cookie> nextCookies = null;
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
            if (cookie.maxAge() != Cookie.UNDEFINED_MAX_AGE && cookie.maxAge() <= 0) {
                setByResponse.add(cookie.name().toLowerCase(Locale.ROOT));
                continue;
            }
            if (nextCookies == null) {
                nextCookies = cookieStore.get(next);
            }
            if (holdsSameValue(nextCookies, cookie)) {
                setByResponse.add(cookie.name().toLowerCase(Locale.ROOT));
            }
        }
        List<Cookie> stored = cookieStore.get(request.getUri());
        List<Cookie> callers = new ArrayList<>(cookies.size());
        for (Cookie cookie : cookies) {
            if (!setByResponse.contains(cookie.name().toLowerCase(Locale.ROOT)) && !holdsSameValue(stored, cookie)) {
                callers.add(cookie);
            }
        }
        return callers;
    }

    /**
     * For a retry of the same request: the caller's cookies, then the store's current ones.
     */
    static void refresh(RequestBuilder retry, Request request, HttpResponse response, CookieStore cookieStore,
                        ClientCookieDecoder cookieDecoder) {
        retry.setCookies(of(request, response, cookieStore, request.getUri(), cookieDecoder));
        retry.setHeader(COOKIE, Collections.emptyList());
        for (Cookie cookie : cookieStore.get(request.getUri())) {
            retry.addCookieIfUnset(cookie);
        }
    }

    /**
     * The request's cookies plus the pairs of a Cookie header the caller set. A hop drops the raw header and
     * sends these instead, reconciled against the response, or the header would outrank a cookie it rotated.
     * Only the request the caller built sends the header as written.
     */
    private static List<Cookie> withHeaderCookies(Request request) {
        List<String> headers = request.getHeaders().getAll(COOKIE);
        if (headers.isEmpty()) {
            return request.getCookies();
        }
        List<Cookie> cookies = new ArrayList<>(request.getCookies());
        for (String header : headers) {
            cookies.addAll(ServerCookieDecoder.LAX.decodeAll(header));
        }
        return cookies;
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
