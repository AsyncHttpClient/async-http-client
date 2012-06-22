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
package com.ning.http.client;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public class Cookie {
    private final String domain;
    private final String name;
    private final String value;
    private final String path;
    private final int maxAge;
    private final boolean secure;
    private final int version;
    private Set<Integer> ports = Collections.emptySet();
    private Set<Integer> unmodifiablePorts = ports;

    public Cookie(String domain, String name, String value, String path, int maxAge, boolean secure) {
        this.domain = domain;
        this.name = name;
        this.value = value;
        this.path = path;
        this.maxAge = maxAge;
        this.secure = secure;
        this.version = 1;
    }

    public Cookie(String domain, String name, String value, String path, int maxAge, boolean secure, int version) {
        this.domain = domain;
        this.name = name;
        this.value = value;
        this.path = path;
        this.maxAge = maxAge;
        this.secure = secure;
        this.version = version;
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

    public Set<Integer> getPorts() {
        if (unmodifiablePorts == null) {
            unmodifiablePorts = Collections.unmodifiableSet(ports);
        }
        return unmodifiablePorts;
    }

    public void setPorts(int... ports) {
        if (ports == null) {
            throw new NullPointerException("ports");
        }

        int[] portsCopy = ports.clone();
        if (portsCopy.length == 0) {
            unmodifiablePorts = this.ports = Collections.emptySet();
        } else {
            Set<Integer> newPorts = new TreeSet<Integer>();
            for (int p : portsCopy) {
                if (p <= 0 || p > 65535) {
                    throw new IllegalArgumentException("port out of range: " + p);
                }
                newPorts.add(Integer.valueOf(p));
            }
            this.ports = newPorts;
            unmodifiablePorts = null;
        }
    }

    public void setPorts(Iterable<Integer> ports) {
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
        return String.format("Cookie: domain=%s, name=%s, value=%s, path=%s, maxAge=%d, secure=%s",
                domain, name, value, path, maxAge, secure);
    }
}
