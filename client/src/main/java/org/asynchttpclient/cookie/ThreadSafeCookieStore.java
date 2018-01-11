/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package org.asynchttpclient.cookie;

import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.uri.Uri;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class ThreadSafeCookieStore implements CookieStore {

    private Map<CookieKey, StoredCookie> cookieJar = new HashMap<>();
    private ReentrantLock lock = new ReentrantLock();

    @Override
    public void add(Uri uri, Cookie cookie) {
        String thisRequestDomain = requestDomain(uri);
        String thisRequestPath = requestPath(uri);

        try {
            lock.lock();
            add(thisRequestDomain, thisRequestPath, cookie);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Cookie> get(Uri uri) {
        try {
            lock.lock();
            return get(requestDomain(uri), requestPath(uri), uri.isSecured());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Cookie> getAll() {
        try {
            lock.lock();
            final boolean[] removeExpired = {false};
            List<Cookie> result = cookieJar
                    .entrySet()
                    .parallelStream()
                    .filter(pair -> {
                        boolean hasCookieExpired = hasCookieExpired(pair.getValue().cookie, pair.getValue().createdAt);
                        if (hasCookieExpired && !removeExpired[0])
                            removeExpired[0] = true;
                        return !hasCookieExpired;
                    })
                    .map(pair -> pair.getValue().cookie)
                    .collect(Collectors.toList());

            if (removeExpired[0])
                removeExpired();

            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean remove(Cookie cookie) {
        try {
            lock.lock();
            return cookieJar.entrySet().removeIf(v -> v.getValue().cookie == cookie);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean clear() {
        try {
            lock.lock();
            boolean result = !cookieJar.isEmpty();
            cookieJar.clear();
            return result;
        } finally {
            lock.unlock();
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private String requestDomain(Uri requestUri) {
        return requestUri.getHost().toLowerCase();
    }

    private String requestPath(Uri requestUri) {
        if (!requestUri.getPath().isEmpty())
            return requestUri.getPath();
        else
            return "/";
    }

    // rfc6265#section-5.2.3
    // Let cookie-domain be the attribute-value without the leading %x2E (".") character.
    private AbstractMap.SimpleEntry<String, Boolean> cookieDomain(String cookieDomain, String requestDomain) {
        if (cookieDomain != null) {
            if (!cookieDomain.isEmpty() && cookieDomain.charAt(0) == '.')
                return new AbstractMap.SimpleEntry<>(cookieDomain.substring(1).toLowerCase(), false);
            else
                return new AbstractMap.SimpleEntry<>(cookieDomain.toLowerCase(), false);
        } else
            return new AbstractMap.SimpleEntry<>(requestDomain, true);
    }

    // rfc6265#section-5.2.4
    private String cookiePath(String rawCookiePath, String requestPath) {
        if (rawCookiePath != null && !rawCookiePath.isEmpty() && rawCookiePath.charAt(0) == '/') {
            return rawCookiePath;
        } else {
            // rfc6265#section-5.1.4
            if (!requestPath.isEmpty() && requestPath.charAt(0) == '/' && requestPath.chars().filter(value -> value == '/').count() > 1)
                return requestPath.substring(0, requestPath.lastIndexOf('/'));
            else
                return "/";
        }
    }

    private boolean hasCookieExpired(Cookie cookie, long whenCreated) {
        // if not specify max-age, this cookie should be discarded when user agent is to be closed, but it is not expired.
        if (cookie.maxAge() == Cookie.UNDEFINED_MAX_AGE)
            return false;

        if (cookie.maxAge() <= 0)
            return true;

        if (whenCreated > 0) {
            long deltaSecond = (System.currentTimeMillis() - whenCreated) / 1000;
            return deltaSecond > cookie.maxAge();
        } else
            return false;
    }

    // rfc6265#section-5.1.3
    // check "The string is a host name (i.e., not an IP address)" ignored
    private boolean domainsMatch(String cookieDomain, String requestDomain, boolean hostOnly) {
        return (hostOnly && Objects.equals(requestDomain, cookieDomain)) ||
                (Objects.equals(requestDomain, cookieDomain) || requestDomain.endsWith("." + cookieDomain));
    }

    // rfc6265#section-5.1.4
    private boolean pathsMatch(String cookiePath, String requestPath) {
        return Objects.equals(cookiePath, requestPath) ||
                (requestPath.startsWith(cookiePath) && (cookiePath.charAt(cookiePath.length() - 1) == '/' || requestPath.charAt(cookiePath.length()) == '/'));
    }

    private static class CookieKey implements Comparable<CookieKey> {
        final String name;
        final String domain;
        final String path;

        CookieKey(String name, String domain, String path) {
            this.name = name;
            this.domain = domain;
            this.path = path;
        }

        @Override
        public int compareTo(CookieKey o) {
            int result;
            if (o != null) {
                if ((result = this.name.compareTo(o.name)) == 0)
                    if ((result = this.domain.compareTo(o.domain)) == 0)
                        result = this.path.compareTo(o.path);
            } else
                throw new NullPointerException();

            return result;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof CookieKey && this.compareTo((CookieKey) obj) == 0;
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + name.hashCode();
            result = 31 * result + domain.hashCode();
            result = 31 * result + path.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return String.format("%s: %s; %s", name, domain, path);
        }
    }

    private static class StoredCookie {
        final Cookie cookie;
        final boolean hostOnly;
        final boolean persistent;
        final long createdAt = System.currentTimeMillis();

        StoredCookie(Cookie cookie, boolean hostOnly, boolean persistent) {
            this.cookie = cookie;
            this.hostOnly = hostOnly;
            this.persistent = persistent;
        }

        @Override
        public String toString() {
            return String.format("%s; hostOnly %s; persistent %s", cookie.toString(), hostOnly, persistent);
        }
    }

    private void add(String requestDomain, String requestPath, Cookie cookie) {

        AbstractMap.SimpleEntry<String, Boolean> pair = cookieDomain(cookie.domain(), requestDomain);
        String keyDomain = pair.getKey();
        boolean hostOnly = pair.getValue();
        String keyPath = cookiePath(cookie.path(), requestPath);
        CookieKey key = new CookieKey(cookie.name().toLowerCase(), keyDomain, keyPath);

        if (hasCookieExpired(cookie, 0))
            cookieJar.remove(key);
        else
            cookieJar.put(key, new StoredCookie(cookie, hostOnly, cookie.maxAge() != Cookie.UNDEFINED_MAX_AGE));
    }

    private List<Cookie> get(String domain, String path, boolean secure) {

        final boolean[] removeExpired = {false};

        List<Cookie> result = cookieJar.entrySet().parallelStream().filter(pair -> {
            CookieKey key = pair.getKey();
            StoredCookie storedCookie = pair.getValue();
            boolean hasCookieExpired = hasCookieExpired(storedCookie.cookie, storedCookie.createdAt);
            if (hasCookieExpired && !removeExpired[0])
                removeExpired[0] = true;
            return !hasCookieExpired && domainsMatch(key.domain, domain, storedCookie.hostOnly) && pathsMatch(key.path, path) && (secure || !storedCookie.cookie.isSecure());
        }).map(v -> v.getValue().cookie).collect(Collectors.toList());

        if (removeExpired[0])
            removeExpired();

        return result;
    }

    private void removeExpired() {
        cookieJar.entrySet().removeIf(v -> hasCookieExpired(v.getValue().cookie, v.getValue().createdAt));
    }
}
