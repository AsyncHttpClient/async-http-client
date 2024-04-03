/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.uri;

import org.jetbrains.annotations.Nullable;

import static java.util.Objects.requireNonNull;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

final class UriParser {

    public @Nullable String scheme;
    public @Nullable String host;
    public int port = -1;
    public @Nullable String query;
    public @Nullable String fragment;
    private @Nullable String authority;
    public String path = "";
    public @Nullable String userInfo;

    private final String originalUrl;
    private int start, end, currentIndex;

    private UriParser(final String originalUrl) {
        this.originalUrl = originalUrl;
    }

    private void trimLeft() {
        while (start < end && originalUrl.charAt(start) <= ' ') {
            start++;
        }

        if (originalUrl.regionMatches(true, start, "url:", 0, 4)) {
            start += 4;
        }
    }

    private void trimRight() {
        end = originalUrl.length();
        while (end > 0 && originalUrl.charAt(end - 1) <= ' ') {
            end--;
        }
    }

    private boolean isFragmentOnly() {
        return start < originalUrl.length() && originalUrl.charAt(start) == '#';
    }

    private static boolean isValidProtocolChar(char c) {
        return Character.isLetterOrDigit(c) && c != '.' && c != '+' && c != '-';
    }

    private static boolean isValidProtocolChars(String protocol) {
        for (int i = 1; i < protocol.length(); i++) {
            if (!isValidProtocolChar(protocol.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidProtocol(String protocol) {
        return protocol.length() > 0 && Character.isLetter(protocol.charAt(0)) && isValidProtocolChars(protocol);
    }

    private void computeInitialScheme() {
        for (int i = currentIndex; i < end; i++) {
            char c = originalUrl.charAt(i);
            if (c == ':') {
                String s = originalUrl.substring(currentIndex, i);
                if (isValidProtocol(s)) {
                    scheme = s.toLowerCase();
                    currentIndex = i + 1;
                }
                break;
            } else if (c == '/') {
                break;
            }
        }
    }

    private boolean overrideWithContext(@Nullable Uri context) {

        boolean isRelative = false;

        // use context only if schemes match
        if (context != null && (scheme == null || scheme.equalsIgnoreCase(context.getScheme()))) {

            // see RFC2396 5.2.3
            String contextPath = context.getPath();
            if (isNonEmpty(contextPath) && contextPath.charAt(0) == '/') {
                scheme = null;
            }

            if (scheme == null) {
                scheme = context.getScheme();
                userInfo = context.getUserInfo();
                host = context.getHost();
                port = context.getPort();
                path = contextPath;
                isRelative = true;
            }
        }
        return isRelative;
    }

    private int findWithinCurrentRange(char c) {
        int pos = originalUrl.indexOf(c, currentIndex);
        return pos > end ? -1 : pos;
    }

    private void trimFragment() {
        int charpPosition = findWithinCurrentRange('#');
        if (charpPosition >= 0) {
            end = charpPosition;
            if (charpPosition + 1 < originalUrl.length()) {
                fragment = originalUrl.substring(charpPosition + 1);
            }
        }
    }

    // isRelative can be true only when context is not null
    @SuppressWarnings("NullAway")
    private void inheritContextQuery(@Nullable Uri context, boolean isRelative) {
        // see RFC2396 5.2.2: query and fragment inheritance
        if (isRelative && currentIndex == end) {
            query = context.getQuery();
            fragment = context.getFragment();
        }
    }

    private boolean computeQuery() {
        if (currentIndex < end) {
            int askPosition = findWithinCurrentRange('?');
            if (askPosition != -1) {
                query = originalUrl.substring(askPosition + 1, end);
                if (end > askPosition) {
                    end = askPosition;
                }
                return askPosition == currentIndex;
            }
        }
        return false;
    }

    private boolean currentPositionStartsWith4Slashes() {
        return originalUrl.regionMatches(currentIndex, "////", 0, 4);
    }

    private boolean currentPositionStartsWith2Slashes() {
        return originalUrl.regionMatches(currentIndex, "//", 0, 2);
    }

    private String computeAuthority() {
        int authorityEndPosition = findWithinCurrentRange('/');
        if (authorityEndPosition == -1) {
            authorityEndPosition = findWithinCurrentRange('?');
            if (authorityEndPosition == -1) {
                authorityEndPosition = end;
            }
        }
        host = authority = originalUrl.substring(currentIndex, authorityEndPosition);
        currentIndex = authorityEndPosition;
        return authority;
    }

    private void computeUserInfo(String nonNullAuthority) {
        int atPosition = nonNullAuthority.indexOf('@');
        if (atPosition != -1) {
            userInfo = nonNullAuthority.substring(0, atPosition);
            host = nonNullAuthority.substring(atPosition + 1);
        } else {
            userInfo = null;
        }
    }

    private static boolean isMaybeIPV6(String nonNullHost) {
        // If the host is surrounded by [ and ] then it's an IPv6
        // literal address as specified in RFC2732
        return nonNullHost.length() > 0 && nonNullHost.charAt(0) == '[';
    }

    private void computeIPV6(String nonNullHost) {
        int positionAfterClosingSquareBrace = nonNullHost.indexOf(']') + 1;
        if (positionAfterClosingSquareBrace > 1) {

            port = -1;

            if (nonNullHost.length() > positionAfterClosingSquareBrace) {
                if (nonNullHost.charAt(positionAfterClosingSquareBrace) == ':') {
                    // see RFC2396: port can be null
                    int portPosition = positionAfterClosingSquareBrace + 1;
                    if (nonNullHost.length() > portPosition) {
                        port = Integer.parseInt(nonNullHost.substring(portPosition));
                    }
                } else {
                    throw new IllegalArgumentException("Invalid authority field: " + authority);
                }
            }

            host = nonNullHost.substring(0, positionAfterClosingSquareBrace);

        } else {
            throw new IllegalArgumentException("Invalid authority field: " + authority);
        }
    }

    private void computeRegularHostPort(String nonNullHost) {
        int colonPosition = nonNullHost.indexOf(':');
        port = -1;
        if (colonPosition >= 0) {
            // see RFC2396: port can be null
            int portPosition = colonPosition + 1;
            if (nonNullHost.length() > portPosition) {
                port = Integer.parseInt(nonNullHost.substring(portPosition));
            }
            host = nonNullHost.substring(0, colonPosition);
        }
    }

    // /./
    private void removeEmbeddedDot() {
        path = path.replace("/./", "/");
    }

    // /../
    private void removeEmbedded2Dots() {
        int i = 0;
        while ((i = path.indexOf("/../", i)) >= 0) {
            if (i > 0) {
                end = path.lastIndexOf('/', i - 1);
                if (end >= 0 && path.indexOf("/../", end) != 0) {
                    path = path.substring(0, end) + path.substring(i + 3);
                    i = 0;
                } else if (end == 0) {
                    break;
                }
            } else {
                i += 3;
            }
        }
    }

    private void removeTailing2Dots() {
        while (path.endsWith("/..")) {
            end = path.lastIndexOf('/', path.length() - 4);
            if (end >= 0) {
                path = path.substring(0, end + 1);
            } else {
                break;
            }
        }
    }

    private void removeStartingDot() {
        if (path.startsWith("./") && path.length() > 2) {
            path = path.substring(2);
        }
    }

    private void removeTrailingDot() {
        if (path.endsWith("/.")) {
            path = path.substring(0, path.length() - 1);
        }
    }

    private void handleRelativePath() {
        int lastSlashPosition = path.lastIndexOf('/');
        String pathEnd = originalUrl.substring(currentIndex, end);

        if (lastSlashPosition == -1) {
            path = authority != null ? '/' + pathEnd : pathEnd;
        } else {
            path = path.substring(0, lastSlashPosition + 1) + pathEnd;
        }
    }

    private void handlePathDots() {
        if (path.indexOf('.') != -1) {
            removeEmbeddedDot();
            removeEmbedded2Dots();
            removeTailing2Dots();
            removeStartingDot();
            removeTrailingDot();
        }
    }

    private void parseAuthority() {
        if (!currentPositionStartsWith4Slashes() && currentPositionStartsWith2Slashes()) {
            currentIndex += 2;

            String nonNullAuthority = computeAuthority();
            computeUserInfo(nonNullAuthority);

            if (host != null) {
                String nonNullHost = host;
                if (isMaybeIPV6(nonNullHost)) {
                    computeIPV6(nonNullHost);
                } else {
                    computeRegularHostPort(nonNullHost);
                }
            }

            if (port < -1) {
                throw new IllegalArgumentException("Invalid port number :" + port);
            }

            // see RFC2396 5.2.4: ignore context path if authority is defined
            if (isNonEmpty(authority)) {
                path = "";
            }
        }
    }

    private void computeRegularPath() {
        if (originalUrl.charAt(currentIndex) == '/') {
            path = originalUrl.substring(currentIndex, end);
        } else if (isNonEmpty(path)) {
            handleRelativePath();
        } else {
            String pathEnd = originalUrl.substring(currentIndex, end);
            path = isNonEmpty(pathEnd) && pathEnd.charAt(0) != '/' ? '/' + pathEnd : pathEnd;
        }
        handlePathDots();
    }

    private void computeQueryOnlyPath() {
        int lastSlashPosition = path.lastIndexOf('/');
        path = lastSlashPosition < 0 ? "/" : path.substring(0, lastSlashPosition) + '/';
    }

    private void computePath(boolean queryOnly) {
        // Parse the file path if any
        if (currentIndex < end) {
            computeRegularPath();
        } else if (queryOnly) {
            computeQueryOnlyPath();
        }
    }

    private void parse(@Nullable Uri context) {
        end = originalUrl.length();

        trimLeft();
        trimRight();
        currentIndex = start;
        if (!isFragmentOnly()) {
            computeInitialScheme();
        }
        boolean isRelative = overrideWithContext(context);
        trimFragment();
        inheritContextQuery(context, isRelative);
        boolean queryOnly = computeQuery();
        parseAuthority();
        computePath(queryOnly);
    }

    public static UriParser parse(@Nullable Uri context, final String originalUrl) {
        requireNonNull(originalUrl, "originalUrl");
        final UriParser parser = new UriParser(originalUrl);
        parser.parse(context);
        return parser;
    }
}