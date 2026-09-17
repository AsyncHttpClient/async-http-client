/*
 *    Copyright (c) 2017-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.cookie;

import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.util.NetUtil;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.MiscUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public final class ThreadSafeCookieStore implements CookieStore {

    // RFC 6265 §5.5 (Implementation Limits) lets a user agent bound the cookies it retains per domain (its
    // floor is "at least 50 per domain"). Capping this keeps a server from growing the jar — and the
    // per-request retrieval scan in get(Uri) — without bound. Chosen generously (well above browser
    // per-domain limits of ~50–180) so it only trips under abuse, never for realistic usage. See
    // evictExcessCookies for the eviction order. Package-private for tests.
    static final int MAX_COOKIES_PER_DOMAIN = 200;

    private final Map<String, Map<CookieKey, StoredCookie>> cookieJar = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();
    // Monotonic per-store stamp giving each stored cookie a strict, tie-free, clock-independent insertion
    // order for eviction (see evictExcessCookies). Preferred over creation time, which is millisecond-
    // granular (so it ties under a flood) and wall-clock based (an NTP step backward would reorder it).
    private final AtomicLong cookieSequence = new AtomicLong();
    private final LongSupplier clock;

    public ThreadSafeCookieStore() {
        this(System::currentTimeMillis);
    }

    // For tests: expiry can be checked without waiting for it.
    ThreadSafeCookieStore(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public void add(Uri uri, Cookie cookie) {
        String thisRequestDomain = requestDomain(uri);
        String thisRequestPath = requestPath(uri);

        add(thisRequestDomain, thisRequestPath, uri.isSecured(), isSecureContext(uri, thisRequestDomain), cookie);
    }

    @Override
    public List<Cookie> get(Uri uri) {
        String domain = requestDomain(uri);
        return get(domain, requestPath(uri), uri.isSecured(), isSecureContext(uri, domain));
    }

    @Override
    public List<Cookie> getAll() {
        return cookieJar.values()
                .stream()
                .flatMap(map -> map.values().stream())
                .filter(pair -> !hasCookieExpired(pair.cookie, pair.createdAt))
                .map(pair -> pair.cookie)
                .collect(Collectors.toList());
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

    // Locale.ROOT, as PublicSuffixList folds: under Turkish "INFO" lowercases to a dotless i and the
    // public-suffix check fails open.
    private static String requestDomain(Uri requestUri) {
        return canonicalHost(requestUri.getHost());
    }

    /**
     * One spelling per host. {@code 127.0.0.1.} resolves like {@code 127.0.0.1} but would slip past the
     * public-suffix and IP checks, and a {@code Location} header lets an attacker pick the spelling.
     */
    private static String canonicalHost(String host) {
        String lowered = host.toLowerCase(Locale.ROOT);
        int end = lowered.length();
        while (end > 0 && lowered.charAt(end - 1) == '.') {
            end--;
        }
        return end == lowered.length() ? lowered : lowered.substring(0, end);
    }

    private static String requestPath(Uri requestUri) {
        return requestUri.getPath().isEmpty() ? "/" : requestUri.getPath();
    }

    // rfc6265#section-5.2.3
    // Let cookie-domain be the attribute-value without the leading %x2E (".") character.
    private static AbstractMap.SimpleEntry<String, Boolean> cookieDomain(@Nullable String cookieDomain, String requestDomain) {
        if (cookieDomain != null) {
            String normalizedCookieDomain = canonicalHost(cookieDomain);
            String domain = !normalizedCookieDomain.isEmpty() && normalizedCookieDomain.charAt(0) == '.' ?
                    normalizedCookieDomain.substring(1) :
                    normalizedCookieDomain;
            // Domain=. leaves nothing, and an empty domain makes the cookie host-only (RFC 6265 section 5.3 step 6).
            if (!domain.isEmpty()) {
                return new AbstractMap.SimpleEntry<>(domain, false);
            }
        }
        return new AbstractMap.SimpleEntry<>(requestDomain, true);
    }

    // rfc6265#section-5.2.4
    private static String cookiePath(@Nullable String rawCookiePath, String requestPath) {
        if (MiscUtils.isNonEmpty(rawCookiePath) && rawCookiePath.charAt(0) == '/') {
            return rawCookiePath;
        } else {
            // rfc6265#section-5.1.4
            int indexOfLastSlash = requestPath.lastIndexOf('/');
            if (!requestPath.isEmpty() && requestPath.charAt(0) == '/' && indexOfLastSlash > 0) {
                return requestPath.substring(0, indexOfLastSlash);
            } else {
                return "/";
            }
        }
    }

    private boolean hasCookieExpired(Cookie cookie, long whenCreated) {
        // if not specify max-age, this cookie should be discarded when user agent is to be closed, but it is not expired.
        if (cookie.maxAge() == Cookie.UNDEFINED_MAX_AGE) {
            return false;
        }

        if (cookie.maxAge() <= 0) {
            return true;
        }

        if (whenCreated > 0) {
            long deltaSecond = (clock.getAsLong() - whenCreated) / 1000;
            return deltaSecond > cookie.maxAge();
        } else {
            return false;
        }
    }

    // rfc6265#section-5.1.3
    private static boolean domainsMatch(String cookieDomain, String requestDomain) {
        if (requestDomain.equals(cookieDomain)) {
            return true;
        }
        // RFC 6265 section 5.1.3: the suffix branch only applies to a host name, not an IP address. Otherwise
        // Domain=1 from 198.51.100.1 reaches every address ending in .1.
        return !isIpAddressLiteral(requestDomain) && requestDomain.endsWith('.' + cookieDomain);
    }

    /**
     * Whether {@code host} could be an IP address. Not {@code NetUtil}: it rejects {@code 127.1},
     * {@code 0177.0.0.1} and {@code 0x7f000001}, which the JDK still resolves through the system resolver,
     * and a rejected host would be treated as a name. A last label that is all digits, or hex after
     * {@code 0x}, is the test; no registrable name has one.
     */
    private static boolean isIpAddressLiteral(String host) {
        if (host.isEmpty()) {
            return false;
        }
        // Uri keeps the brackets on an IPv6 literal.
        if (host.charAt(0) == '[' || host.indexOf(':') >= 0) {
            return true;
        }
        int lastLabel = host.lastIndexOf('.') + 1;
        if (lastLabel >= host.length()) {
            return false;
        }
        boolean hex = host.startsWith("0x", lastLabel);
        for (int i = hex ? lastLabel + 2 : lastLabel; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!(c >= '0' && c <= '9' || hex && c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    // rfc6265#section-5.1.4
    private static boolean pathsMatch(String cookiePath, String requestPath) {
        return Objects.equals(cookiePath, requestPath) ||
                requestPath.startsWith(cookiePath) && (cookiePath.charAt(cookiePath.length() - 1) == '/' || requestPath.charAt(cookiePath.length()) == '/');
    }

    /**
     * https, wss, or plaintext to loopback, as rfc6265bis allows and curl does, so a development server on
     * {@code http://localhost} gets back the Secure cookies it set. It never gets one that came over TLS; see
     * {@link StoredCookie#overTls}.
     */
    private static boolean isSecureContext(Uri uri, String canonicalHost) {
        return uri.isSecured() || isLoopbackHost(canonicalHost);
    }

    /**
     * Exactly {@code localhost}, or a loopback address literal. Parsed, never looked up: {@code 127.0.0.256}
     * is not a literal to the JDK, so it resolves as a name and may go anywhere. Unlike
     * {@link #isIpAddressLiteral}, a strict parser fails closed here.
     */
    private static boolean isLoopbackHost(String host) {
        if ("localhost".equals(host)) {
            return true;
        }
        String literal = host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']'
                ? host.substring(1, host.length() - 1) : host;
        byte[] address = NetUtil.createByteArrayFromIpAddressString(literal);
        try {
            return address != null && InetAddress.getByAddress(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private void add(String requestDomain, String requestPath, boolean requestTls, boolean requestSecure, Cookie cookie) {
        // rfc6265bis section 5.7 step 13: a Secure cookie counts only from a secure context.
        if (cookie.isSecure() && !requestSecure) {
            return;
        }

        AbstractMap.SimpleEntry<String, Boolean> pair = cookieDomain(cookie.domain(), requestDomain);
        String keyDomain = pair.getKey();
        boolean hostOnly = pair.getValue();

        // rfc6265#section-5.3 step 6: ignore a cookie whose Domain attribute is not
        // domain-matched by the request host, otherwise a host can plant cookies for
        // unrelated domains (cookie tossing).
        if (!hostOnly && !domainsMatch(keyDomain, requestDomain)) {
            return;
        }

        // rfc6265#section-5.3 step 5: a Domain naming a public suffix must not be honoured. Step 6 above
        // only asks whether the request host sits under the Domain, which evil.co.uk setting Domain=co.uk
        // satisfies, so on its own it still lets one site plant a cookie every other site under that
        // registry receives. Label counting cannot stand in: co.uk has a dot like any other domain.
        //
        // The step also says that when the Domain equals the request host the cookie is kept, as a
        // host-only cookie, rather than discarded. That case is not an attack and dropping it would break
        // ordinary single-label hosts: dev, app, box, cloud and a dozen more are ICANN suffixes as well as
        // the short names Docker Compose and Kubernetes hand out. It is kept host-only, or a service reached
        // as bare "app" would hand it to every *.app.
        if (!hostOnly && PublicSuffixList.isPublicSuffix(keyDomain)) {
            if (!keyDomain.equals(requestDomain)) {
                return;
            }
            hostOnly = true;
        }

        String keyPath = cookiePath(cookie.path(), requestPath);
        // Cookie names are case-sensitive; folding them is an old deviation, kept so existing applications
        // send what they did. ROOT only stops a Turkish default locale missing the key.
        CookieKey key = new CookieKey(cookie.name().toLowerCase(Locale.ROOT), keyPath);

        // Step 16: a cookie from plaintext may not overlay a Secure one either; from loopback, only one that came
        // over TLS. Before the expiry branch, so Max-Age=0 cannot delete it.
        if (!requestTls && shadowsSecureCookie(keyDomain, key, requestSecure)) {
            return;
        }

        if (hasCookieExpired(cookie, 0)) {
            cookieJar.getOrDefault(keyDomain, Collections.emptyMap()).remove(key);
        } else {
            final Map<CookieKey, StoredCookie> innerMap = cookieJar.computeIfAbsent(keyDomain, domain -> new ConcurrentHashMap<>());
            innerMap.put(key, new StoredCookie(cookie, hostOnly, cookie.maxAge() != Cookie.UNDEFINED_MAX_AGE, clock.getAsLong(),
                    cookieSequence.getAndIncrement(), requestSecure, requestTls));
            if (innerMap.size() > MAX_COOKIES_PER_DOMAIN) {
                evictExcessCookies(innerMap);
            }
        }
    }

    /**
     * Bounds a single domain's cookie bucket at {@link #MAX_COOKIES_PER_DOMAIN}. RFC 6265 §5.5 permits a
     * per-domain cap; §5.3's "remove excess cookies" step evicts expired cookies first, then removes more
     * until under the limit. The RFC breaks that second tie by least-recently-accessed; we do not track
     * access time, so we deliberately deviate and evict in insertion order via the strict, tie-free
     * {@link StoredCookie#seq} stamp. Non-Secure cookies go first, as rfc6265bis section 5.7 requires: otherwise
     * a plaintext response floods the bucket, evicts a Secure cookie and then overlays it past step 16.
     *
     * <p>Called from {@link #add} right after an insert pushes the bucket over the cap, so it normally
     * removes a single entry. A single pass drops expired entries and collects the survivors; only if those
     * still exceed the cap are they ordered by {@code seq} and the oldest excess removed. Victims are dropped
     * with the two-arg {@code remove(key, value)}, which is identity-based (StoredCookie has no
     * {@code equals()}): a cookie another thread just re-put under the same key is never collaterally
     * removed. Two adders evicting concurrently pick the same seq-ordered victims, so their redundant removes
     * no-op — the bucket may still briefly sit a little below the cap until the next add, but never grows
     * unbounded.
     */
    private void evictExcessCookies(Map<CookieKey, StoredCookie> innerMap) {
        List<Map.Entry<CookieKey, StoredCookie>> live = new ArrayList<>(innerMap.size());
        for (Map.Entry<CookieKey, StoredCookie> entry : innerMap.entrySet()) {
            if (hasCookieExpired(entry.getValue().cookie, entry.getValue().createdAt)) {
                innerMap.remove(entry.getKey(), entry.getValue());
            } else {
                live.add(entry);
            }
        }
        int excess = live.size() - MAX_COOKIES_PER_DOMAIN;
        if (excess <= 0) {
            return;
        }
        live.sort(Comparator.<Map.Entry<CookieKey, StoredCookie>>comparingInt(entry -> entry.getValue().cookie.isSecure() ? 1 : 0)
                .thenComparingLong(entry -> entry.getValue().seq));
        for (int i = 0; i < excess; i++) {
            Map.Entry<CookieKey, StoredCookie> victim = live.get(i);
            innerMap.remove(victim.getKey(), victim.getValue());
        }
    }

    /**
     * rfc6265bis section 5.7 step 16. The path test is one-way, so a plaintext cookie for {@code /} can still sit
     * beside a Secure one for {@code /account}; the order {@code get} returns them in keeps the Secure one first.
     * Walks the whole jar, since subdomain entries cannot be reached by walking up.
     */
    private boolean shadowsSecureCookie(String cookieDomain, CookieKey newKey, boolean fromLoopback) {
        for (Map.Entry<String, Map<CookieKey, StoredCookie>> domainEntry : cookieJar.entrySet()) {
            String storedDomain = domainEntry.getKey();
            if (!domainsMatch(cookieDomain, storedDomain) && !domainsMatch(storedDomain, cookieDomain)) {
                continue;
            }
            for (Map.Entry<CookieKey, StoredCookie> entry : domainEntry.getValue().entrySet()) {
                CookieKey storedKey = entry.getKey();
                StoredCookie storedCookie = entry.getValue();
                if (storedCookie.cookie.isSecure()
                        && (!fromLoopback || storedCookie.overTls)
                        && storedKey.name.equals(newKey.name)
                        && pathsMatch(storedKey.path, newKey.path)
                        && !hasCookieExpired(storedCookie.cookie, storedCookie.createdAt)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Cookie> get(String domain, String path, boolean tls, boolean secure) {
        boolean exactDomainMatch = true;
        String subDomain = domain;
        List<Map.Entry<CookieKey, StoredCookie>> results = null;
        // RFC 6265 section 5.4 selects by the same domain-match, so an IP takes only its own entry rather than
        // walking 127.0.0.1 -> 0.0.1 -> 0.1 -> 1. Not redundant with storage: 0.1 may set Domain=0.1 legitimately.
        boolean walkParents = !isIpAddressLiteral(domain);

        while (MiscUtils.isNonEmpty(subDomain)) {
            // Lazily allocate a single result list and append matches straight into it; an imperative
            // scan avoids the per-sub-domain-level Stream pipeline (filter/map stages, two capturing
            // lambdas, a spliterator and the Collectors.toList intermediate list) that this ran on every
            // cookie-enabled request.
            if (results == null) {
                results = new ArrayList<>(4);
            }
            int from = results.size();
            collectStoredCookies(subDomain, path, tls, secure, exactDomainMatch, results);
            if (results.size() - from > 1) {
                results.subList(from, results.size()).sort(LONGER_PATH_FIRST);
            }
            if (!walkParents) {
                break;
            }
            subDomain = DomainUtils.getSubDomain(subDomain);
            exactDomainMatch = false;
        }

        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        if (results.size() == 1) {
            return Collections.singletonList(results.get(0).getValue().cookie);
        }
        // The order decides which cookie is sent, since addCookieIfUnset keeps only the first of each name:
        // 1. on a secure request, cookies received in a secure context (provenance, not the Secure attribute);
        // 2. the host's own bucket before its parents', so a cookie tossed up to a parent cannot displace a
        //    subdomain's own. The apex's own host-only cookie shares the tossed one's key, so it can be replaced;
        // 3. within a bucket, longer paths first (RFC 6265 section 5.4).
        // One global path sort, as the RFC describes, would break rule 2.
        if (secure) {
            // Stable, so rules 2 and 3 hold within each group.
            results.sort(SECURE_CONTEXT_FIRST);
        }
        List<Cookie> cookies = new ArrayList<>(results.size());
        for (Map.Entry<CookieKey, StoredCookie> entry : results) {
            cookies.add(entry.getValue().cookie);
        }
        return Collections.unmodifiableList(cookies);
    }

    private static final Comparator<Map.Entry<CookieKey, StoredCookie>> SECURE_CONTEXT_FIRST =
            Comparator.comparingInt(entry -> entry.getValue().secureContext ? 0 : 1);

    // By the key's path, which is the defaulted one; Cookie.path() is null when Set-Cookie had no Path.
    private static final Comparator<Map.Entry<CookieKey, StoredCookie>> LONGER_PATH_FIRST =
            Comparator.<Map.Entry<CookieKey, StoredCookie>>comparingInt(entry -> entry.getKey().path.length())
                    .reversed()
                    .thenComparingLong(entry -> entry.getValue().seq);

    private void collectStoredCookies(String domain, String path, boolean tls, boolean secure, boolean isExactMatch,
                                      List<Map.Entry<CookieKey, StoredCookie>> out) {
        final Map<CookieKey, StoredCookie> innerMap = cookieJar.get(domain);
        if (innerMap == null) {
            return;
        }

        for (Map.Entry<CookieKey, StoredCookie> entry : innerMap.entrySet()) {
            CookieKey key = entry.getKey();
            StoredCookie storedCookie = entry.getValue();
            if (!hasCookieExpired(storedCookie.cookie, storedCookie.createdAt)
                    && (isExactMatch || !storedCookie.hostOnly)
                    && pathsMatch(key.path, path)
                    && (tls || !storedCookie.cookie.isSecure() || (secure && !storedCookie.overTls))) {
                out.add(entry);
            }
        }
    }

    private void removeExpired() {
        final boolean[] removed = {false};

        cookieJar.values()
                .forEach(cookieMap -> removed[0] |= cookieMap.entrySet()
                        .removeIf(v -> hasCookieExpired(v.getValue().cookie, v.getValue().createdAt)));

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
        public int compareTo(@NotNull CookieKey cookieKey) {
            requireNonNull(cookieKey, "Parameter can't be null");

            int result;
            if ((result = name.compareTo(cookieKey.name)) == 0) {
                result = path.compareTo(cookieKey.path);
            }
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof CookieKey && compareTo((CookieKey) obj) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, path);
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
        final long createdAt;
        // Strict, tie-free insertion order for eviction; see ThreadSafeCookieStore.cookieSequence.
        final long seq;
        /** Received in a secure context, so it ranks first on a secure request. */
        final boolean secureContext;
        /** Arrived over TLS, so it is never sent in plaintext, loopback included. */
        final boolean overTls;

        StoredCookie(Cookie cookie, boolean hostOnly, boolean persistent, long createdAt, long seq,
                     boolean secureContext, boolean overTls) {
            this.secureContext = secureContext;
            this.overTls = overTls;
            this.cookie = cookie;
            this.hostOnly = hostOnly;
            this.persistent = persistent;
            this.createdAt = createdAt;
            this.seq = seq;
        }

        @Override
        public String toString() {
            return String.format("%s; hostOnly %s; persistent %s", cookie.toString(), hostOnly, persistent);
        }
    }

    public static final class DomainUtils {
        private static final char DOT = '.';

        public static @Nullable String getSubDomain(@Nullable String domain) {
            if (domain == null || domain.isEmpty()) {
                return null;
            }
            final int indexOfDot = domain.indexOf(DOT);
            if (indexOfDot == -1) {
                return null;
            }
            return domain.substring(indexOfDot + 1);
        }

        private DomainUtils() {
        }
    }
}
