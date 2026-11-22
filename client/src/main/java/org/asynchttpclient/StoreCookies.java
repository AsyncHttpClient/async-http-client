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

import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.cookie.CookieStore;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

/**
 * Tells the cookies the client added to a request from its {@link CookieStore} from those the application or a
 * filter set. The store selected them for the URI the request had then, so a request sent to another URI must not
 * carry them. They are marked rather than recognised by value: the response that leads to another request may have
 * rotated the value in the store already. A marked cookie delegates to the store's instance.
 * <p>
 * Internal to the client; applications have no use for it.
 */
public final class StoreCookies {

    private StoreCookies() {
    }

    /**
     * Marks a cookie taken from the store. A marked cookie is returned as it is.
     *
     * @param cookie a cookie the store returned
     * @return the cookie, marked
     */
    public static Cookie mark(Cookie cookie) {
        return cookie instanceof Marked ? cookie : new Marked(cookie);
    }

    /**
     * @param cookie a cookie of a request
     * @return whether the client added it from the store
     */
    public static boolean isMarked(Cookie cookie) {
        return cookie instanceof Marked;
    }

    /**
     * Returns the request with the cookies the store added replaced by the store's for the request's URI, of the
     * names the request does not carry otherwise. Cookies the store did not add are kept. Without a store, those it
     * added are only removed.
     * <p>
     * {@code previous} is the request this one was made from, as it was sent, if there is one. If both go to the same
     * origin, a cookie of the store that {@code previous} carried and this request no longer carries is not added
     * back: a filter removed it.
     *
     * @param request     the request to send
     * @param previous    the request it was made from, or null
     * @param cookieStore the client's store, or null
     * @return the request, or a copy that differs from it by its cookies only
     */
    public static Request rebind(Request request, @Nullable Request previous, @Nullable CookieStore cookieStore) {
        List<Cookie> cookies = cookiesOf(request);
        List<Cookie> stored = cookieStore == null ? Collections.emptyList() : cookieStore.get(request.getUri());
        Set<String> removed = previous != null && previous != request && previous.getUri().isSameBase(request.getUri())
                ? removedNames(cookiesOf(previous), cookies) : Collections.emptySet();
        List<Cookie> rebound = null;
        for (int i = 0; i < cookies.size(); i++) {
            Cookie cookie = cookies.get(i);
            if (cookie instanceof Marked) {
                if (rebound == null) {
                    rebound = new ArrayList<>(cookies.size() + stored.size());
                    rebound.addAll(cookies.subList(0, i));
                }
            } else if (rebound != null) {
                rebound.add(cookie);
            }
        }
        for (Cookie cookie : stored) {
            if (!removed.contains(fold(cookie.name()))
                    && !hasCookieNamed(rebound == null ? cookies : rebound, cookie.name())) {
                if (rebound == null) {
                    rebound = new ArrayList<>(cookies.size() + stored.size());
                    rebound.addAll(cookies);
                }
                rebound.add(mark(cookie));
            }
        }
        if (rebound == null) {
            return request;
        }
        // A DefaultRequest is copied with only its cookie list replaced, which leaves its URI as built; any other
        // Request goes through toBuilder(), as it may override what the builder copies.
        if (request.getClass() == DefaultRequest.class) {
            return ((DefaultRequest) request).withCookies(rebound);
        }
        return request.toBuilder().setCookies(rebound).build();
    }

    private static List<Cookie> cookiesOf(Request request) {
        // A DefaultRequest from the public constructor may carry null.
        return isNonEmpty(request.getCookies()) ? request.getCookies() : Collections.emptyList();
    }

    /**
     * The names of the store's cookies that {@code sent} carried and {@code cookies} no longer carries, folded as the
     * store keys names, so that a value it now holds under another case is not added back either.
     */
    private static Set<String> removedNames(List<Cookie> sent, List<Cookie> cookies) {
        Set<String> removed = null;
        for (Cookie cookie : sent) {
            if (cookie instanceof Marked) {
                if (removed == null) {
                    removed = new HashSet<>(4);
                }
                removed.add(fold(cookie.name()));
            }
        }
        if (removed == null) {
            return Collections.emptySet();
        }
        for (Cookie cookie : cookies) {
            if (cookie instanceof Marked) {
                removed.remove(fold(cookie.name()));
            }
        }
        return removed;
    }

    private static String fold(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static boolean hasCookieNamed(List<Cookie> cookies, String name) {
        for (Cookie cookie : cookies) {
            if (cookie.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static final class Marked implements Cookie {

        private final Cookie cookie;

        Marked(Cookie cookie) {
            this.cookie = cookie;
        }

        @Override
        public String name() {
            return cookie.name();
        }

        @Override
        public String value() {
            return cookie.value();
        }

        @Override
        public void setValue(String value) {
            cookie.setValue(value);
        }

        @Override
        public boolean wrap() {
            return cookie.wrap();
        }

        @Override
        public void setWrap(boolean wrap) {
            cookie.setWrap(wrap);
        }

        @Override
        public @Nullable String domain() {
            return cookie.domain();
        }

        @Override
        public void setDomain(String domain) {
            cookie.setDomain(domain);
        }

        @Override
        public @Nullable String path() {
            return cookie.path();
        }

        @Override
        public void setPath(String path) {
            cookie.setPath(path);
        }

        @Override
        public long maxAge() {
            return cookie.maxAge();
        }

        @Override
        public void setMaxAge(long maxAge) {
            cookie.setMaxAge(maxAge);
        }

        @Override
        public boolean isSecure() {
            return cookie.isSecure();
        }

        @Override
        public void setSecure(boolean secure) {
            cookie.setSecure(secure);
        }

        @Override
        public boolean isHttpOnly() {
            return cookie.isHttpOnly();
        }

        @Override
        public void setHttpOnly(boolean httpOnly) {
            cookie.setHttpOnly(httpOnly);
        }

        // Unwrapped, so that a marked cookie compares and equals as the store's own does, against either.
        @Override
        public int compareTo(Cookie other) {
            return cookie.compareTo(other instanceof Marked ? ((Marked) other).cookie : other);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other != null && cookie.equals(other instanceof Marked ? ((Marked) other).cookie : other);
        }

        @Override
        public int hashCode() {
            return cookie.hashCode();
        }

        @Override
        public String toString() {
            return cookie.toString();
        }
    }
}
