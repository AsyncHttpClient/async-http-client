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

import static org.asynchttpclient.cookie.CookieUtil.*;

public class Cookie {

    public static Cookie newValidCookie(String name, String value, boolean wrap, String domain, String path, long maxAge, boolean secure, boolean httpOnly) {
        return new Cookie(validateCookieName(name), validateCookieValue(value), wrap, validateCookieAttribute("domain", domain), validateCookieAttribute("path", path), maxAge, secure, httpOnly);
    }

    private final String name;
    private final String value;
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
