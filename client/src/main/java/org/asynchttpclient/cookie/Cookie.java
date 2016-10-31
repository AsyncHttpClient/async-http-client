/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.cookie;

import static org.asynchttpclient.cookie.CookieUtil.validateCookieAttribute;
import static org.asynchttpclient.cookie.CookieUtil.validateCookieName;
import static org.asynchttpclient.cookie.CookieUtil.validateCookieValue;

public class Cookie {

    /**
     * Method to get a {@link org.asynchttpclient.cookie.Cookie} from {@code javax.servlet} package's {@link javax.servlet.http.Cookie}
     *
     * @param cookie base entity to convert
     * @param wrap true if the value of this {@link Cookie} is to be wrapped with double quotes.
     * @return converted {@link Cookie}
     */
    public static Cookie newCookie(javax.servlet.http.Cookie cookie, boolean wrap) {
        return new Cookie(cookie.getName(), cookie.getValue(), wrap, cookie.getDomain(), cookie.getPath(), cookie.getMaxAge(), cookie.getSecure(), cookie.isHttpOnly());
    }

    /**
     * Method to get a valid {@link org.asynchttpclient.cookie.Cookie} from {@code javax.servlet} package's {@link javax.servlet.http.Cookie}
     *
     * @param cookie base entity to convert
     * @param wrap true if the value of this {@link Cookie} is to be wrapped with double quotes.
     * @return converted {@link Cookie}
     * @throws IllegalArgumentException if any part of {@code cookie} is invalid
     */
    public static Cookie newValidCookie(javax.servlet.http.Cookie cookie, boolean wrap) {
        return new Cookie(validateCookieName(cookie.getName()), validateCookieValue(cookie.getValue()), wrap, validateCookieAttribute("domain", cookie.getDomain()), validateCookieAttribute("path", cookie.getPath()), cookie.getMaxAge(), cookie.getSecure(), cookie.isHttpOnly());
    }

    /**
     * Method to get a {@link org.asynchttpclient.cookie.Cookie} from Netty's {@link io.netty.handler.codec.http.cookie.Cookie}
     *
     * @param cookie base entity to convert
     * @return converted {@link Cookie}
     */
    public static Cookie newCookie(io.netty.handler.codec.http.cookie.Cookie cookie) {
        return new Cookie(cookie.name(), cookie.value(), cookie.wrap(), cookie.domain(), cookie.path(), cookie.maxAge(), cookie.isSecure(), cookie.isHttpOnly());
    }

    /**
     * Method to get a valid {@link org.asynchttpclient.cookie.Cookie} from Netty's {@link io.netty.handler.codec.http.cookie.Cookie}
     *
     * @param cookie base entity to convert
     * @return converted {@link Cookie}
     * @throws IllegalArgumentException if any part of {@code cookie} is invalid
     */
    public static Cookie newValidCookie(io.netty.handler.codec.http.cookie.Cookie cookie) {
        return new Cookie(validateCookieName(cookie.name()), validateCookieValue(cookie.value()), cookie.wrap(), validateCookieAttribute("domain", cookie.domain()), validateCookieAttribute("path", cookie.path()), cookie.maxAge(), cookie.isSecure(), cookie.isHttpOnly());
    }

    public static Cookie newValidCookie(String name, String value, boolean wrap, String domain, String path, long maxAge, boolean secure, boolean httpOnly) {
        return new Cookie(validateCookieName(name), validateCookieValue(value), wrap, validateCookieAttribute("domain", domain), validateCookieAttribute("path", path), maxAge, secure, httpOnly);
    }

    private final String name;
    private final String value;
    /**
     * If value should be wrapped with double quotes during serialization
     */
    private final boolean wrap;
    private final String domain;
    private final String path;
    private final long maxAge;
    private final boolean secure;
    private final boolean httpOnly;

    public Cookie(String name, String value, boolean wrap, String domain, String path, long maxAge, boolean secure, boolean httpOnly) {
        this.name = name;
        this.value = value;
        this.wrap = wrap;
        this.domain = domain;
        this.path = path;
        this.maxAge = maxAge;
        this.secure = secure;
        this.httpOnly = httpOnly;
    }

    public String getDomain() {
        return domain;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    /**
     * @return true if the value of this {@link Cookie} is to be wrapped with double quotes.
     */
    public boolean isWrap() {
        return wrap;
    }

    public String getPath() {
        return path;
    }

    public long getMaxAge() {
        return maxAge;
    }

    public boolean isSecure() {
        return secure;
    }

    public boolean isHttpOnly() {
        return httpOnly;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(name);
        buf.append('=');
        if (wrap)
            buf.append('"').append(value).append('"');
        else
            buf.append(value);
        if (domain != null) {
            buf.append("; domain=");
            buf.append(domain);
        }
        if (path != null) {
            buf.append("; path=");
            buf.append(path);
        }
        if (maxAge >= 0) {
            buf.append("; maxAge=");
            buf.append(maxAge);
            buf.append('s');
        }
        if (secure) {
            buf.append("; secure");
        }
        if (httpOnly) {
            buf.append("; HTTPOnly");
        }
        return buf.toString();
    }
}
