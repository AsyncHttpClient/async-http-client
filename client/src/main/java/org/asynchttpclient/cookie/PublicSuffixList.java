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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The ICANN section of the Mozilla Public Suffix List, used to decide whether a cookie {@code Domain}
 * attribute names a registry rather than a site.
 *
 * <p>RFC 6265 Section 5.3 step 5 requires rejecting a {@code Domain} that is a public suffix, and that
 * rule cannot be approximated: {@code co.uk} has a dot like any ordinary domain, so counting labels does
 * not distinguish a registry from a site. Without the list a host under {@code co.uk} can set a cookie for
 * {@code co.uk} itself and every other host under that suffix receives it.
 *
 * <p>Only the ICANN section is bundled. The private section describes organisations that let others
 * register names beneath them, which is a weaker property than a registry and not what step 5 is about.
 *
 * <p>Matching lowercases with {@link Locale#ROOT}. The default locale would be wrong here in a way that
 * matters: under Turkish, {@code "INFO".toLowerCase()} is not {@code info}, so the check would answer
 * false for every I-initial suffix and fail open exactly where it is meant to hold.
 *
 * <p>The list is data and goes stale as registries change. A suffix added upstream after this release is
 * not recognised until the bundled copy is refreshed, so this narrows the exposure rather than closing it
 * for all time. If the resource cannot be read the check reports nothing as a public suffix, leaving
 * behaviour as it was rather than rejecting cookies that used to work.
 */
public final class PublicSuffixList {

  private static final Logger LOGGER = LoggerFactory.getLogger(PublicSuffixList.class);
  private static final String RESOURCE = "/org/asynchttpclient/cookie/public_suffix_list.dat";

  private static final Set<String> EXACT;
  private static final Set<String> WILDCARD;
  private static final Set<String> EXCEPTIONS;

  static {
      Set<String> exact = new HashSet<>(8192);
      Set<String> wildcard = new HashSet<>(32);
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

  private PublicSuffixList() {
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
      // An exception rule names something that IS registrable despite matching a wildcard above it.
      if (EXCEPTIONS.contains(candidate)) {
      return false;
      }
      if (EXACT.contains(candidate)) {
      return true;
      }
      // A wildcard rule such as *.ck makes every direct child of ck a suffix, so the candidate is one
      // when its parent carries the rule.
      int dot = candidate.indexOf('.');
      return dot > 0 && WILDCARD.contains(candidate.substring(dot + 1));
  }
}
