package com.ning.http.client;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

class UnmodifiableHeaders extends Headers {
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
    public Headers replaceAll(Headers src)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Headers replaceAll(Map<? extends String, ? extends Collection<String>> src)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> put(String key, List<String> value)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(Map<? extends String, ? extends List<String>> values)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Headers delete(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Headers deleteAll(String... names)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Headers deleteAll(Collection<String> names)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> remove(Object key)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<Map.Entry<String, List<String>>> iterator() {
        return headers.iterator();
    }

    @Override
    public Set<String> keySet()
    {
        return Collections.unmodifiableSet(headers.keySet());
    }

    @Override
    public Set<Entry<String, List<String>>> entrySet()
    {
        return Collections.unmodifiableSet(headers.entrySet());
    }

    @Override
    public int size()
    {
        return headers.size();
    }

    @Override
    public boolean isEmpty()
    {
        return headers.isEmpty();
    }

    @Override
    public boolean isDefined(String name)
    {
        return headers.isDefined(name);
    }

    @Override
    public boolean containsKey(Object key)
    {
        return headers.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value)
    {
        return headers.containsValue(value);
    }

    @Override
    public String getFirstHeaderValue(String name)
    {
        return headers.getFirstHeaderValue(name);
    }

    @Override
    public String getHeaderValue(String name) {
        return headers.getHeaderValue(name);
    }

    @Override
    public List<String> getHeaderValues(String name) {
        return headers.getHeaderValues(name);
    }

    @Override
    public List<String> get(Object key)
    {
        return headers.get(key);
    }

    @Override
    public Collection<List<String>> values()
    {
        return headers.values();
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
    public Set<String> getHeaderNames()
    {
        return headers.getHeaderNames();
    }

    @Override
    public String toString()
    {
        return headers.toString();
    }
}