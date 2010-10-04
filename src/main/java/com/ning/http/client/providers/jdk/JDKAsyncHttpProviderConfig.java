package com.ning.http.client.providers.jdk;

import com.ning.http.client.AsyncHttpProviderConfig;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class JDKAsyncHttpProviderConfig implements AsyncHttpProviderConfig<String,String> {

    private final ConcurrentHashMap<String, String> properties = new ConcurrentHashMap<String, String>();


    public AsyncHttpProviderConfig addProperty(String name, String value) {
        properties.put(name, value);
        return this;
    }

    public String getProperty(String name) {
        return properties.get(name);
    }

    public String removeProperty(String name) {
        return properties.remove(name);
    }

    public Set<Map.Entry<String, String>> propertiesSet() {
        return properties.entrySet();
    }
}
