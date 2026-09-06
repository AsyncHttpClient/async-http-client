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
package org.asynchttpclient.cookie;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.IDN;
import java.util.Collections;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The Mozilla Public Suffix List, used to decide whether a cookie {@code Domain} attribute names a
 * registry rather than a site.
 *
 * <p>RFC 6265 Section 5.3 step 5 requires rejecting a {@code Domain} that is a public suffix, and that
 * rule cannot be approximated: {@code co.uk} has a dot like any ordinary domain, so counting labels does
 * not distinguish a registry from a site. Without the list a host under {@code co.uk} can set a cookie for
 * {@code co.uk} itself and every other host under that suffix receives it.
 *
 * <p>Both sections are bundled. The private one lists hosts such as {@code github.io} whose subdomains
 * belong to different people, which is the same hazard.
 *
 * <p>Internationalised rules are matched as written and as A-labels, since only the A-label gets past the
 * cookie decoder. Matching lowercases with {@link Locale#ROOT}, and callers must fold the same way: under
 * Turkish, {@code "INFO".toLowerCase()} is not {@code info}.
 *
 * <p>The list is data and goes stale as registries change. If it cannot be read, only single-label names
 * are treated as public suffixes, and the warning logged at load says so.
 */
public final class PublicSuffixList {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicSuffixList.class);
    private static final String RESOURCE = "/org/asynchttpclient/cookie/public_suffix_list.dat";

    private static final Set<String> EXACT;
    private static final Set<String> WILDCARD;
    private static final Set<String> EXCEPTIONS;

    static {
        Set<String> exact = new HashSet<>(1 << 14);
        Set<String> wildcard = new HashSet<>(64);
        Set<String> exceptions = new HashSet<>(16);
        try (InputStream in = PublicSuffixList.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("Public suffix list {} is missing; a cookie Domain naming a public suffix "
                        + "cannot be rejected", RESOURCE);
            } else {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    String rule = line.trim();
                    if (rule.isEmpty() || rule.startsWith("//")) {
                        continue;
                    }
                    // The list's format: a line is only read up to the first whitespace.
                    int space = indexOfWhitespace(rule);
                    if (space >= 0) {
                        rule = rule.substring(0, space);
                        if (rule.isEmpty()) {
                            continue;
                        }
                    }
                    if (rule.charAt(0) == '!') {
                        exceptions.add(rule.substring(1).toLowerCase(Locale.ROOT));
                    } else if (rule.startsWith("*.")) {
                        wildcard.add(rule.substring(2).toLowerCase(Locale.ROOT));
                    } else {
                        exact.add(rule.toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read the public suffix list; a cookie Domain naming a public suffix "
                    + "cannot be rejected", e);
        }
        EXACT = Collections.unmodifiableSet(exact);
        WILDCARD = Collections.unmodifiableSet(wildcard);
        EXCEPTIONS = Collections.unmodifiableSet(exceptions);
    }

    /**
     * The rules keyed by A-label, built on the first A-label query because {@link IDN} takes a few hundred
     * milliseconds to warm up. The rules are converted, not the query: {@link IDN#toUnicode} does not
     * round-trip every rule.
     */
    private static final class AsciiForms {
        static final Set<String> EXACT = toAsciiForms(PublicSuffixList.EXACT);
        static final Set<String> WILDCARD = toAsciiForms(PublicSuffixList.WILDCARD);
        static final Set<String> EXCEPTIONS = toAsciiForms(PublicSuffixList.EXCEPTIONS);

        private static Set<String> toAsciiForms(Set<String> rules) {
            Set<String> asciiForms = new HashSet<>(1024);
            for (String rule : rules) {
                if (isAscii(rule)) {
                    continue;
                }
                try {
                    // The JDK's tables are Unicode 3.2, and the list uses code points assigned since.
                    asciiForms.add(IDN.toASCII(rule, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT));
                } catch (RuntimeException e) {
                    LOGGER.debug("Public suffix rule {} has no A-label form; it is only matched as written", rule, e);
                }
            }
            return Collections.unmodifiableSet(asciiForms);
        }
    }

    private PublicSuffixList() {
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** Index of the first space or tab, the only whitespace the list's format uses, or -1. */
    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether {@code domain} is a public suffix, and so may not be the {@code Domain} of a cookie.
     *
     * @param domain a hostname, without a leading dot
     */
    public static boolean isPublicSuffix(String domain) {
        if (domain == null || domain.isEmpty()) {
            return false;
        }
        String candidate = domain.toLowerCase(Locale.ROOT);
        if (candidate.charAt(candidate.length() - 1) == '.') {
            candidate = candidate.substring(0, candidate.length() - 1);
        }

        // An A-label is asked about both tables, as one rule set: an exception in either outranks the rest.
        boolean alsoAsAscii = candidate.contains("xn--");
        // An exception rule names something that IS registrable despite matching a wildcard above it.
        if (EXCEPTIONS.contains(candidate) || alsoAsAscii && AsciiForms.EXCEPTIONS.contains(candidate)) {
            return false;
        }
        @Nullable Boolean matched = matchAgainst(candidate, EXACT, WILDCARD);
        if (matched == null && alsoAsAscii) {
            matched = matchAgainst(candidate, AsciiForms.EXACT, AsciiForms.WILDCARD);
        }
        if (matched != null) {
            return matched;
        }

        // The list's default rule is "*": a bare label nothing matched is a suffix. Without it a site under a
        // wildcard-only TLD such as .ck sets a cookie for every .ck site. add() still keeps a Domain equal to
        // the request host, as host-only.
        return candidate.indexOf('.') < 0;
    }

    /**
     * Exact and wildcard rules; the caller checks exceptions, which outrank both tables.
     *
     * @return {@code TRUE} when a rule matched, {@code null} when none did
     */
    private static @Nullable Boolean matchAgainst(String candidate, Set<String> exact, Set<String> wildcard) {
        if (exact.contains(candidate)) {
            return Boolean.TRUE;
        }
        // A wildcard rule such as *.ck makes every direct child of ck a suffix, so the candidate is one
        // when its parent carries the rule.
        int dot = candidate.indexOf('.');
        if (dot > 0 && wildcard.contains(candidate.substring(dot + 1))) {
            return Boolean.TRUE;
        }
        return null;
    }
}
