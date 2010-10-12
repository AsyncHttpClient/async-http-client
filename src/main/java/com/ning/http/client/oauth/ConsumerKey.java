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
package com.ning.http.client.oauth;

/**
 * Value class for OAuth consumer keys.
 */
public class ConsumerKey {
    private final String key;
    private final String secret;

    public ConsumerKey(String key, String secret) {
        this.key = key;
        this.secret = secret;
    }

    public String getKey() {
        return key;
    }

    public String getSecret() {
        return secret;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{Consumer key, key=");
        appendValue(sb, key);
        sb.append(", secret=");
        appendValue(sb, secret);
        sb.append("}");
        return sb.toString();
    }

    private void appendValue(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"');
            sb.append(value);
            sb.append('"');
        }
    }

    @Override
    public int hashCode() {
        return key.hashCode() + secret.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (o == null || o.getClass() != getClass()) return false;
        ConsumerKey other = (ConsumerKey) o;
        return key.equals(other.key) && secret.equals(other.secret);
    }
}
