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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Headers implements Iterable<Map.Entry<String, List<String>>> {
    private final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
    private final Map<String, String> headerNames = new LinkedHashMap<String, String>();

    public static Headers unmodifiableHeaders(Headers headers) {
        return new UnmodifiableHeaders(headers);
    }

    public Headers() {
    }

    public Headers(Headers src) {
        if (src != null) {
            for (Map.Entry<String, List<String>> header : src) {
                add(header.getKey(), header.getValue());
            }
        }
    }

    public Headers(Map<String, Collection<String>> src) {
        if (src != null) {
            for (Map.Entry<String, Collection<String>> header : src.entrySet()) {
                add(header.getKey(), header.getValue());
            }
        }
    }

    /**
     * Adds the specified header and returns this headers object.
     *
     * @param name   The header name
     * @param values The header value; if null then this method has no effect. Use the empty string to
     *               generate an empty header value
     * @return This object
     */
    public Headers add(String name, String... values) {
        if ((values != null) && (values.length > 0)) {
            add(name, Arrays.asList(values));
        }
        return this;
    }

    private List<String> getNonNullValues(Collection<String> values) {
        List<String> result = null;

        if (values != null) {
            for (String value : values) {
                if (value != null) {
                    if (result == null) {
                        // lazy initialization
                        result = new ArrayList<String>();
                    }
                    result.add(value);
                }
            }
        }
        return result;
    }

    /**
     * Adds the specified header values and returns this headers object.
     *
     * @param name   The header name
     * @param values The header values; if null then this method has no effect. Use an empty collection
     *               to generate an empty header value
     * @return This object
     */
    public Headers add(String name, Collection<String> values) {
        List<String> nonNullValues = getNonNullValues(values);

        if (nonNullValues != null) {
            String       key       = name.toLowerCase();
            String       usedName  = headerNames.get(key);
            List<String> curValues = null;

            if (usedName == null) {
                usedName = name;
                headerNames.put(key, name);
            }
            else {
                curValues = headers.get(usedName);
            }
    
            if (curValues == null) {
                curValues = new ArrayList<String>();
                headers.put(usedName, curValues);
            }
            curValues.addAll(nonNullValues);
        }
        return this;
    }

    /**
     * Adds all headers from the given headers object to this object and returns this headers object.
     *
     * @param src The source headers object
     * @return This object
     */
    public Headers addAll(Headers src) {
        if (src != null) {
            for (Map.Entry<String, List<String>> header : src) {
                add(header.getKey(), header.getValue());
            }
        }
        return this;
    }

    /**
     * Adds all headers from the given map to this object and returns this headers object.
     *
     * @param src The source map of headers
     * @return This object
     */
    public Headers addAll(Map<String, Collection<String>> src) {
        if (src != null) {
            for (Map.Entry<String, Collection<String>> header : src.entrySet()) {
                add(header.getKey(), header.getValue());
            }
        }
        return this;
    }

    /**
     * Replaces the indicated header with the given values.
     *
     * @param name   The header name
     * @param values The new header values
     * @return This object
     */
    public Headers replace(final String name, final String... values) {
        return replace(name, Arrays.asList(values));
    }

    /**
     * Replaces the indicated header with the given values.
     *
     * @param name  The header name
     * @param values The new header value
     * @return This object
     */
    public Headers replace(final String name, final Collection<String> values) {
        List<String> nonNullValues = getNonNullValues(values);
        String       key           = name.toLowerCase();
        String       usedName      = headerNames.get(key);

        if (nonNullValues == null) {
            headerNames.remove(key);
            if (usedName != null) {
                headers.remove(usedName);
            }
        }
        else {
            if (!name.equals(usedName)) {
                headerNames.put(key, name);
                headers.remove(usedName);
            }
            headers.put(name, nonNullValues);
        }

        return this;
    }

    /**
     * Replaces all headers present the given headers object in this object and returns this headers object.
     *
     * @param src The source headers object
     * @return This object
     */
    public Headers replaceAll(Headers src) {
        if (src != null) {
            for (Map.Entry<String, List<String>> header : src) {
                replace(header.getKey(), header.getValue());
            }
        }
        return this;
    }

    /**
     * Replaces all headers from the given map in this object and returns this headers object.
     *
     * @param src The source map of headers
     * @return This object
     */
    public Headers replaceAll(Map<String, Collection<String>> src) {
        if (src != null) {
            for (Map.Entry<String, Collection<String>> header : src.entrySet()) {
                replace(header.getKey(), header.getValue());
            }
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public Iterator<Map.Entry<String, List<String>>> iterator() {
        return Collections.unmodifiableSet(headers.entrySet()).iterator();
    }

    /**
     * Returns the names of all defined headers.
     * 
     * @return The header names
     */
    public Set<String> getHeaderNames() {
        return new LinkedHashSet<String>(headerNames.values());
    }

    /**
     * Determines whether the indicated header is defined.
     * 
     * @param name The header name
     * @return {@code true} if the header is defined
     */
    public boolean isDefined(String name) {
        return headerNames.containsKey(name.toLowerCase());
    }

    /**
     * Returns the (first) value of the header of the given name. If there are multiple values
     * for that header, then the first one will be returned. This method is more appropriate for
     * certain headers like Content-Type or Content-Length where multiple values can break
     * servers/clients.
     *
     * @param name The header's name
     * @return The (first) header value; {@code null} if this header is not defined
     */
    public String getFirstHeaderValue(String name) {
        List<String> values = getHeaderValues(name);

        if (values == null) {
            return null;
        }
        else if (values.isEmpty()) {
            return "";
        }
        else {
            return values.get(0);
        }
    }

    /**
     * Returns the value of the header of the given name. If there are multiple values
     * for that header, then they will be concatenated according to the RFC????.
     *
     * @param name The header's name
     * @return The header value; {@code null} if this header is not defined
     */
    public String getHeaderValue(String name) {
        List<String> values = getHeaderValues(name);
        
        if (values == null) {
            return null;
        }
        else if (values.size() == 1) {
            return values.get(0);
        }
        else {
            StringBuilder result = new StringBuilder();

            for (String value : values) {
                if (result.length() > 0) {
                    result.append(", ");
                }
                result.append(value);
            }
            return result.toString();
        }
    }

    /**
     * Returns all defined values for the specified header.
     *
     * @param name The header name
     * @return The values, or {@code null} if the header is not defined
     */
    public List<String> getHeaderValues(String name) {
        String key      = name.toLowerCase();
        String usedName = headerNames.get(key);

        if (usedName == null) {
            return null;
        }
        else {
            List<String> values = headers.get(usedName);

            return values == null ? Collections.<String>emptyList() : Collections.unmodifiableList(values);
        }
    }

    /**
     * Removes the specified header if present and returns this headers object.
     *
     * @param name The header name
     * @return This object
     */
    public Headers remove(String name) {
        String key      = name.toLowerCase();
        String usedName = headerNames.remove(key);

        if (usedName != null) {
            headers.remove(usedName);
        }
        return this;
    }

    /**
     * Removed all specified headers in this object and returns this headers object.
     *
     * @param names The header names
     * @return This object
     */
    public Headers removeAll(String... names) {
        if (names != null) {
            for (String name : names) {
                remove(name);
            }
        }
        return this;
    }

    /**
     * Removed all specified headers in this object and returns this headers object.
     *
     * @param names The header names
     * @return This object
     */
    public Headers removeAll(Collection<String> names) {
        if (names != null) {
            for (String name : names) {
                remove(name);
            }
        }
        return this;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        final Headers other = (Headers) obj;
        if (headers == null) {
            if (other.headers != null)
                return false;
        } else if (!headers.equals(other.headers))
            return false;
        return true;
    }

    @Override
    public int hashCode() {
        return headers == null ? 0 : headers.hashCode();
    }

    @Override
    public String toString()
    {
        StringBuilder result = new StringBuilder();

        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (result.length() > 0) {
                result.append("; ");
            }
            result.append("\"");
            result.append(entry.getKey());
            result.append("=");

            boolean needsComma = false;

            for (String value : entry.getValue()) {
                if (needsComma) {
                    result.append(", ");
                }
                else {
                    needsComma = true;
                }
                result.append(value);
            }
            result.append("\"");
        }
        return result.toString();
    }

    private static class UnmodifiableHeaders extends Headers {
        final Headers headers;

        UnmodifiableHeaders(Headers headers) {
            this.headers = headers;
        }

        @Override
        public Headers add(String name, String... values) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Headers add(String name, Collection<String> values) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Headers addAll(Headers src) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Headers addAll(Map<String, Collection<String>> src) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean equals(Object obj) {
            return (obj instanceof Headers) && headers.equals(obj);
        }

        @Override
        public int hashCode() {
            return headers.hashCode();
        }

        @Override
        public String getHeaderValue(String name) {
            return headers.getHeaderValue(name);
        }

        @Override
        public Set<String> getHeaderNames()
        {
            return headers.getHeaderNames();
        }

        @Override
        public List<String> getHeaderValues(String name) {
            return headers.getHeaderValues(name);
        }

        @Override
        public boolean isDefined(String name)
        {
            return headers.isDefined(name);
        }

        @Override
        public Iterator<Map.Entry<String, List<String>>> iterator() {
            return headers.iterator();
        }

        @Override
        public Headers remove(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Headers replace(String header, String... values)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Headers replace(String header, Collection<String> values)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString()
        {
            return headers.toString();
        }
    }
}
