package org.asynchttpclient.simple;

/*
 * Copyright (c) 2010 Sonatype, Inc. All rights reserved.
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

import org.asynchttpclient.FluentCaseInsensitiveStringsMap;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A map containing headers with the sole purpose of being given to
 * {@link SimpleAHCTransferListener#onHeaders(org.asynchttpclient.uri.Uri, HeaderMap)}.
 *
 * @author Benjamin Hanzelmann
 */
public class HeaderMap implements Map<String, List<String>> {

    private FluentCaseInsensitiveStringsMap headers;

    public HeaderMap(FluentCaseInsensitiveStringsMap headers) {
        this.headers = headers;
    }

    public Set<String> keySet() {
        return headers.keySet();
    }

    public Set<java.util.Map.Entry<String, List<String>>> entrySet() {
        return headers.entrySet();
    }

    public int size() {
        return headers.size();
    }

    public boolean isEmpty() {
        return headers.isEmpty();
    }

    public boolean containsKey(Object key) {
        return headers.containsKey(key);
    }

    public boolean containsValue(Object value) {
        return headers.containsValue(value);
    }

    /**
     * @see FluentCaseInsensitiveStringsMap#getFirstValue(String)
     * @param key The key
     * @return The first value
     */
    public String getFirstValue(String key) {
        return headers.getFirstValue(key);
    }

    /**
     * @see FluentCaseInsensitiveStringsMap#getJoinedValue(String, String)
     * 
     * @param key The key
     * @param delimiter The delimiter for joining the values
     * @return The value as a single string
     */
    public String getJoinedValue(String key, String delimiter) {
        return headers.getJoinedValue(key, delimiter);
    }

    @Override
    public List<String> get(Object key) {
        return headers.get(key);
    }

    /**
     * Only read access is supported.
     * 
     * {@inheritDoc}
     */
    @Override
    public List<String> put(String key, List<String> value) {
        throw new UnsupportedOperationException("Only read access is supported.");
    }

    /**
     * Only read access is supported.
     * 
     * {@inheritDoc}
     */
    @Override
    public List<String> remove(Object key) {
        throw new UnsupportedOperationException("Only read access is supported.");
    }

    /**
     * Only read access is supported.
     * 
     * {@inheritDoc}
     */
    @Override
    public void putAll(Map<? extends String, ? extends List<String>> t) {
        throw new UnsupportedOperationException("Only read access is supported.");

    }

    /**
     * Only read access is supported.
     * 
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        throw new UnsupportedOperationException("Only read access is supported.");
    }

    /**
     * Only read access is supported.
     * 
     * {@inheritDoc}
     */
    @Override
    public Collection<List<String>> values() {
        return headers.values();
    }
}
