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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An implementation of a {@code String -> List<String>} map that adds a fluent interface, i.e. methods that
 * return this instance.
 */
public class FluentStringsMap implements Map<String, List<String>>, Iterable<Map.Entry<String, List<String>>> {
    private final Map<String, List<String>> values = new LinkedHashMap<String, List<String>>();

    public FluentStringsMap() {
    }

    public FluentStringsMap(FluentStringsMap src) {
        if (src != null) {
            for (Map.Entry<String, List<String>> header : src) {
                add(header.getKey(), header.getValue());
            }
        }
    }

    public FluentStringsMap(Map<String, Collection<String>> src) {
        if (src != null) {
            for (Map.Entry<String, Collection<String>> header : src.entrySet()) {
                add(header.getKey(), header.getValue());
            }
        }
    }

    /**
     * Adds the specified values and returns this object.
     *
     * @param key    The key
     * @param values The value(s); if null then this method has no effect. Use the empty string to
     *               generate an empty value
     * @return This object
     */
    public FluentStringsMap add(String key, String... values) {
        if ((values != null) && (values.length > 0)) {
            add(key, Arrays.asList(values));
        }
        return this;
    }

    private List<String> fetchValues(Collection<String> values) {
        List<String> result = null;

        if (values != null) {
            for (String value : values) {
                if (value == null) {
                    value = "";
                }
                if (result == null) {
                    // lazy initialization
                    result = new ArrayList<String>();
                }
                result.add(value);
            }
        }
        return result;
    }

    /**
     * Adds the specified values and returns this object.
     *
     * @param key    The key
     * @param values The value(s); if null then this method has no effect. Use an empty collection
     *               to generate an empty value
     * @return This object
     */
    public FluentStringsMap add(String key, Collection<String> values) {
        if (key != null) {
            List<String> nonNullValues = fetchValues(values);

            if (nonNullValues != null) {
                List<String> curValues = this.values.get(key);

                if (curValues == null) {
                    curValues = new ArrayList<String>();
                    this.values.put(key, curValues);
                }
                curValues.addAll(nonNullValues);
            }
        }
        return this;
    }

    /**
     * Adds all key-values pairs from the given object to this object and returns this object.
     *
     * @param src The source object
     * @return This object
     */
    public FluentStringsMap addAll(FluentStringsMap src) {
        if (src != null) {
            for (Map.Entry<String, List<String>> header : src) {
                add(header.getKey(), header.getValue());
            }
        }
        return this;
    }

    /**
     * Adds all key-values pairs from the given map to this object and returns this object.
     *
     * @param src The source map
     * @return This object
     */
    public FluentStringsMap addAll(Map<String, Collection<String>> src) {
        if (src != null) {
            for (Map.Entry<String, Collection<String>> header : src.entrySet()) {
                add(header.getKey(), header.getValue());
            }
        }
        return this;
    }

    /**
     * Replaces the values for the given key with the given values.
     *
     * @param key    The key
     * @param values The new values
     * @return This object
     */
    public FluentStringsMap replace(final String key, final String... values) {
        return replace(key, Arrays.asList(values));
    }

    /**
     * Replaces the values for the given key with the given values.
     *
     * @param key    The key
     * @param values The new values
     * @return This object
     */
    public FluentStringsMap replace(final String key, final Collection<String> values) {
        if (key != null) {
            List<String> nonNullValues = fetchValues(values);

            if (nonNullValues == null) {
                this.values.remove(key);
            } else {
                this.values.put(key, nonNullValues);
            }
        }
        return this;
    }

    /**
     * Replace the values for all keys from the given map that are also present in this object, with the values from the given map.
     * All key-values from the given object that are not present in this object, will be added to it.
     *
     * @param src The source object
     * @return This object
     */
    public FluentStringsMap replaceAll(FluentStringsMap src) {
        if (src != null) {
            for (Map.Entry<String, List<String>> header : src) {
                replace(header.getKey(), header.getValue());
            }
        }
        return this;
    }

    /**
     * Replace the values for all keys from the given map that are also present in this object, with the values from the given map.
     * All key-values from the given object that are not present in this object, will be added to it.
     *
     * @param src The source map
     * @return This object
     */
    public FluentStringsMap replaceAll(Map<? extends String, ? extends Collection<String>> src) {
        if (src != null) {
            for (Map.Entry<? extends String, ? extends Collection<String>> header : src.entrySet()) {
                replace(header.getKey(), header.getValue());
            }
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public List<String> put(String key, List<String> value) {
        if (key == null) {
            throw new NullPointerException("Null keys are not allowed");
        }

        List<String> oldValue = get(key);

        replace(key, value);
        return oldValue;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public void putAll(Map<? extends String, ? extends List<String>> values) {
        replaceAll(values);
    }

    /**
     * Removes the values for the given key if present and returns this object.
     *
     * @param key The key
     * @return This object
     */
    public FluentStringsMap delete(String key) {
        values.remove(key);
        return this;
    }

    /**
     * Removes the values for the given keys if present and returns this object.
     *
     * @param keys The keys
     * @return This object
     */
    public FluentStringsMap deleteAll(String... keys) {
        if (keys != null) {
            for (String key : keys) {
                remove(key);
            }
        }
        return this;
    }

    /**
     * Removes the values for the given keys if present and returns this object.
     *
     * @param keys The keys
     * @return This object
     */
    public FluentStringsMap deleteAll(Collection<String> keys) {
        if (keys != null) {
            for (String key : keys) {
                remove(key);
            }
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public List<String> remove(Object key) {
        if (key == null) {
            return null;
        } else {
            List<String> oldValues = get(key.toString());

            delete(key.toString());
            return oldValues;
        }
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public void clear() {
        values.clear();
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public Iterator<Map.Entry<String, List<String>>> iterator() {
        return Collections.unmodifiableSet(values.entrySet()).iterator();
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public Set<String> keySet() {
        return Collections.unmodifiableSet(values.keySet());
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public Set<Entry<String, List<String>>> entrySet() {
        return values.entrySet();
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public int size() {
        return values.size();
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean containsKey(Object key) {
        return key == null ? false : values.containsKey(key.toString());
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean containsValue(Object value) {
        return values.containsValue(value);
    }

    /**
     * Returns the value for the given key. If there are multiple values for this key,
     * then only the first one will be returned.
     *
     * @param key The key
     * @return The first value
     */
    public String getFirstValue(String key) {
        List<String> values = get(key);

        if (values == null) {
            return null;
        } else if (values.isEmpty()) {
            return "";
        } else {
            return values.get(0);
        }
    }

    /**
     * Returns the values for the given key joined into a single string using the given delimiter.
     *
     * @param key The key
     * @return The value as a single string
     */
    public String getJoinedValue(String key, String delimiter) {
        List<String> values = get(key);

        if (values == null) {
            return null;
        } else if (values.size() == 1) {
            return values.get(0);
        } else {
            StringBuilder result = new StringBuilder();

            for (String value : values) {
                if (result.length() > 0) {
                    result.append(delimiter);
                }
                result.append(value);
            }
            return result.toString();
        }
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public List<String> get(Object key) {
        if (key == null) {
            return null;
        }

        return values.get(key.toString());
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public Collection<List<String>> values() {
        return values.values();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        final FluentStringsMap other = (FluentStringsMap) obj;

        if (values == null) {
            if (other.values != null) {
                return false;
            }
        } else if (!values.equals(other.values)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return values == null ? 0 : values.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();

        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
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
                } else {
                    needsComma = true;
                }
                result.append(value);
            }
            result.append("\"");
        }
        return result.toString();
    }
}
