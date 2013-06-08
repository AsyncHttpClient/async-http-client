/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package org.asynchttpclient;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public class Cookie implements Comparable<Cookie>{
    private final String domain;
    private final String name;
    private final String value;
    private final String rawValue;
    private final String path;
    private final int maxAge;
    private final boolean secure;
    private final int version;
    private final boolean httpOnly;
    private final boolean discard;
    private final String comment;
    private final String commentUrl;

    private Set<Integer> ports = Collections.emptySet();
    private Set<Integer> unmodifiablePorts = ports;

    public Cookie(String domain, String name, String value, String path, int maxAge, boolean secure) {
        this(domain, name, value, path, maxAge, secure, 1);
    }

    public Cookie(String domain, String name, String value, String path, int maxAge, boolean secure, int version) {
        this(domain, name, value, value, path, maxAge, secure, version, false, false, null, null, Collections.<Integer> emptySet());
    }

    public Cookie(String domain, String name, String value, String rawValue, String path, int maxAge, boolean secure, int version, boolean httpOnly, boolean discard, String comment, String commentUrl, Iterable<Integer> ports) {

        if (name == null) {
            throw new NullPointerException("name");
        }
        name = name.trim();
        if (name.length() == 0) {
            throw new IllegalArgumentException("empty name");
        }

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c > 127) {
                throw new IllegalArgumentException("name contains non-ascii character: " + name);
            }

            // Check prohibited characters.
            switch (c) {
            case '\t':
            case '\n':
            case 0x0b:
            case '\f':
            case '\r':
            case ' ':
            case ',':
            case ';':
            case '=':
                throw new IllegalArgumentException("name contains one of the following prohibited characters: " + "=,; \\t\\r\\n\\v\\f: " + name);
            }
        }

        if (name.charAt(0) == '$') {
            throw new IllegalArgumentException("name starting with '$' not allowed: " + name);
        }

        if (value == null) {
            throw new NullPointerException("value");
        }

        this.name = name;
        this.value = value;
        this.rawValue = rawValue;
        this.domain = validateValue("domain", domain);
        this.path = validateValue("path", path);
        this.maxAge = maxAge;
        this.secure = secure;
        this.version = version;
        this.httpOnly = httpOnly;

        if (version > 0) {
            this.comment = validateValue("comment", comment);
        } else {
            this.comment = null;
        }
        if (version > 1) {
            this.discard = discard;
            this.commentUrl = validateValue("commentUrl", commentUrl);
            setPorts(ports);
        } else {
            this.discard = false;
            this.commentUrl = null;
        }
    }

    public String getDomain() {
        return domain;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public String getValue() {
        return value == null ? "" : value;
    }
    
    public String getRawValue() {
        return rawValue;
    }

    public String getPath() {
        return path;
    }

    public int getMaxAge() {
        return maxAge;
    }

    public boolean isSecure() {
        return secure;
    }

    public int getVersion() {
        return version;
    }

    public String getComment() {
        return this.comment;
    }

    public String getCommentUrl() {
        return this.commentUrl;
    }

    public boolean isHttpOnly() {
        return httpOnly;
    }

    public boolean isDiscard() {
        return discard;
    }

    public Set<Integer> getPorts() {
        if (unmodifiablePorts == null) {
            unmodifiablePorts = Collections.unmodifiableSet(ports);
        }
        return unmodifiablePorts;
    }

    private void setPorts(Iterable<Integer> ports) {
        Set<Integer> newPorts = new TreeSet<Integer>();
        for (int p : ports) {
            if (p <= 0 || p > 65535) {
                throw new IllegalArgumentException("port out of range: " + p);
            }
            newPorts.add(Integer.valueOf(p));
        }
        if (newPorts.isEmpty()) {
            unmodifiablePorts = this.ports = Collections.emptySet();
        } else {
            this.ports = newPorts;
            unmodifiablePorts = null;
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(getName());
        buf.append('=');
        buf.append(getValue());
        if (getDomain() != null) {
            buf.append("; domain=");
            buf.append(getDomain());
        }
        if (getPath() != null) {
            buf.append("; path=");
            buf.append(getPath());
        }
        if (getComment() != null) {
            buf.append("; comment=");
            buf.append(getComment());
        }
        if (getMaxAge() >= 0) {
            buf.append("; maxAge=");
            buf.append(getMaxAge());
            buf.append('s');
        }
        if (isSecure()) {
            buf.append("; secure");
        }
        if (isHttpOnly()) {
            buf.append("; HTTPOnly");
        }
        return buf.toString();
    }

    private String validateValue(String name, String value) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        if (value.length() == 0) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
            case '\r':
            case '\n':
            case '\f':
            case 0x0b:
            case ';':
                throw new IllegalArgumentException(name + " contains one of the following prohibited characters: " + ";\\r\\n\\f\\v (" + value + ')');
            }
        }
        return value;
    }

    public int compareTo(Cookie c) {
        int v;
        v = getName().compareToIgnoreCase(c.getName());
        if (v != 0) {
            return v;
        }

        if (getPath() == null) {
            if (c.getPath() != null) {
                return -1;
            }
        } else if (c.getPath() == null) {
            return 1;
        } else {
            v = getPath().compareTo(c.getPath());
            if (v != 0) {
                return v;
            }
        }

        if (getDomain() == null) {
            if (c.getDomain() != null) {
                return -1;
            }
        } else if (c.getDomain() == null) {
            return 1;
        } else {
            v = getDomain().compareToIgnoreCase(c.getDomain());
            return v;
        }

        return 0;
    }
}