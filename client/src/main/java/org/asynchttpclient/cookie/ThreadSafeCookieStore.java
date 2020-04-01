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
import org.asynchttpclient.util.Assertions;
import org.asynchttpclient.util.MiscUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class ThreadSafeCookieStore implements CookieStore {

  private final Map<String, Map<CookieKey, StoredCookie>> cookieJar = new ConcurrentHashMap<>();
  private final AtomicInteger counter = new AtomicInteger();

  @Override
  public void add(Uri uri, Cookie cookie) {
    String thisRequestDomain = requestDomain(uri);
    String thisRequestPath = requestPath(uri);

    add(thisRequestDomain, thisRequestPath, cookie);
  }

  @Override
  public List<Cookie> get(Uri uri) {
    return get(requestDomain(uri), requestPath(uri), uri.isSecured());
  }

  @Override
  public List<Cookie> getAll() {
    List<Cookie> result = cookieJar
            .values()
            .stream()
            .flatMap(map -> map.values().stream())
            .filter(pair -> !hasCookieExpired(pair.cookie, pair.createdAt))
            .map(pair -> pair.cookie)
            .collect(Collectors.toList());

    return result;
  }

  @Override
  public boolean remove(Predicate<Cookie> predicate) {
    final boolean[] removed = {false};
    cookieJar.forEach((key, value) -> {
      if (!removed[0]) {
        removed[0] = value.entrySet().removeIf(v -> predicate.test(v.getValue().cookie));
      }
    });
    if (removed[0]) {
      cookieJar.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());
    }
    return removed[0];
  }

  @Override
  public boolean clear() {
    boolean result = !cookieJar.isEmpty();
    cookieJar.clear();
    return result;
  }

  @Override
  public void evictExpired() {
    removeExpired();
  }


  @Override
  public int incrementAndGet() {
    return counter.incrementAndGet();
  }

  @Override
  public int decrementAndGet() {
    return counter.decrementAndGet();
  }

  @Override
  public int count() {
    return counter.get();
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Map<String, Map<CookieKey, StoredCookie>> getUnderlying() {
    return new HashMap<>(cookieJar);
  }

  private String requestDomain(Uri requestUri) {
    return requestUri.getHost().toLowerCase();
  }

  private String requestPath(Uri requestUri) {
    return requestUri.getPath().isEmpty() ? "/" : requestUri.getPath();
  }

  // rfc6265#section-5.2.3
  // Let cookie-domain be the attribute-value without the leading %x2E (".") character.
  private AbstractMap.SimpleEntry<String, Boolean> cookieDomain(String cookieDomain, String requestDomain) {
    if (cookieDomain != null) {
      String normalizedCookieDomain = cookieDomain.toLowerCase();
      return new AbstractMap.SimpleEntry<>(
              (!cookieDomain.isEmpty() && cookieDomain.charAt(0) == '.') ?
                      normalizedCookieDomain.substring(1) :
                      normalizedCookieDomain, false);
    } else
      return new AbstractMap.SimpleEntry<>(requestDomain, true);
  }

  // rfc6265#section-5.2.4
  private String cookiePath(String rawCookiePath, String requestPath) {
    if (MiscUtils.isNonEmpty(rawCookiePath) && rawCookiePath.charAt(0) == '/') {
      return rawCookiePath;
    } else {
      // rfc6265#section-5.1.4
      int indexOfLastSlash = requestPath.lastIndexOf('/');
      if (!requestPath.isEmpty() && requestPath.charAt(0) == '/' && indexOfLastSlash > 0)
        return requestPath.substring(0, indexOfLastSlash);
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

  // rfc6265#section-5.1.4
  private boolean pathsMatch(String cookiePath, String requestPath) {
    return Objects.equals(cookiePath, requestPath) ||
            (requestPath.startsWith(cookiePath) && (cookiePath.charAt(cookiePath.length() - 1) == '/' || requestPath.charAt(cookiePath.length()) == '/'));
  }

  private void add(String requestDomain, String requestPath, Cookie cookie) {
    AbstractMap.SimpleEntry<String, Boolean> pair = cookieDomain(cookie.domain(), requestDomain);
    String keyDomain = pair.getKey();
    boolean hostOnly = pair.getValue();
    String keyPath = cookiePath(cookie.path(), requestPath);
    CookieKey key = new CookieKey(cookie.name().toLowerCase(), keyPath);

    if (hasCookieExpired(cookie, 0))
      cookieJar.getOrDefault(keyDomain, Collections.emptyMap()).remove(key);
    else {
      final Map<CookieKey, StoredCookie> innerMap = cookieJar.computeIfAbsent(keyDomain, domain -> new ConcurrentHashMap<>());
      innerMap.put(key, new StoredCookie(cookie, hostOnly, cookie.maxAge() != Cookie.UNDEFINED_MAX_AGE));
    }
  }

  private List<Cookie> get(String domain, String path, boolean secure) {
    boolean exactDomainMatch = true;
    String subDomain = domain;
    List<Cookie> results = null;

    while (MiscUtils.isNonEmpty(subDomain)) {
      final List<Cookie> storedCookies = getStoredCookies(subDomain, path, secure, exactDomainMatch);
      subDomain = DomainUtils.getSubDomain(subDomain);
      exactDomainMatch = false;
      if (storedCookies.isEmpty()) {
        continue;
      }
      if (results == null) {
        results = new ArrayList<>(4);
      }
      results.addAll(storedCookies);
    }

    return results == null ? Collections.emptyList() : results;
  }

  private List<Cookie> getStoredCookies(String domain, String path, boolean secure, boolean isExactMatch) {
    final Map<CookieKey, StoredCookie> innerMap = cookieJar.get(domain);
    if (innerMap == null) {
      return Collections.emptyList();
    }

    return innerMap.entrySet().stream().filter(pair -> {
      CookieKey key = pair.getKey();
      StoredCookie storedCookie = pair.getValue();
      boolean hasCookieExpired = hasCookieExpired(storedCookie.cookie, storedCookie.createdAt);
      return !hasCookieExpired &&
             (isExactMatch || !storedCookie.hostOnly) &&
             pathsMatch(key.path, path) &&
             (secure || !storedCookie.cookie.isSecure());
    }).map(v -> v.getValue().cookie).collect(Collectors.toList());
  }

  private void removeExpired() {
    final boolean[] removed = {false};
    cookieJar.values().forEach(cookieMap -> removed[0] |= cookieMap.entrySet().removeIf(
            v -> hasCookieExpired(v.getValue().cookie, v.getValue().createdAt)));
    if (removed[0]) {
      cookieJar.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());
    }
  }

  private static class CookieKey implements Comparable<CookieKey> {
    final String name;
    final String path;

    CookieKey(String name, String path) {
      this.name = name;
      this.path = path;
    }

    @Override
    public int compareTo(CookieKey o) {
      Assertions.assertNotNull(o, "Parameter can't be null");
      int result;
      if ((result = this.name.compareTo(o.name)) == 0)
          result = this.path.compareTo(o.path);

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
      result = 31 * result + path.hashCode();
      return result;
    }

    @Override
    public String toString() {
      return String.format("%s: %s", name, path);
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

  public static final class DomainUtils {
    private static final char DOT = '.';
      public static String getSubDomain(String domain) {
        if (domain == null || domain.isEmpty()) {
          return null;
        }
        final int indexOfDot = domain.indexOf(DOT);
        if (indexOfDot == -1) {
          return null;
        }
        return domain.substring(indexOfDot + 1);
      }

    private DomainUtils() {}
  }
}
