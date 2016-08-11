package org.asynchttpclient.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class AsyncHttpClientConfigHelper {

    private static volatile Config config;

    public static Config getAsyncHttpClientConfig() {
        if (config == null) {
            config = new Config();
        }

        return config;
    }

    /**
     * This method invalidates the property caches. So if a system property has
     * been changed and the effect of this change is to be seen then call
     * reloadProperties() and then getAsyncHttpClientConfig() to get the new
     * property values.
     */
    public static void reloadProperties() {
        if (config != null)
            config.reload();
    }

    public static class Config {

        public static final String DEFAULT_AHC_PROPERTIES = "ahc-default.properties";
        public static final String CUSTOM_AHC_PROPERTIES = "ahc.properties";

        private final ConcurrentHashMap<String, String> propsCache = new ConcurrentHashMap<>();
        private final Properties defaultProperties = parsePropertiesFile(DEFAULT_AHC_PROPERTIES);
        private volatile Properties customProperties = parsePropertiesFile(CUSTOM_AHC_PROPERTIES);

        public void reload() {
            customProperties = parsePropertiesFile(CUSTOM_AHC_PROPERTIES);
            propsCache.clear();
        }

        private Properties parsePropertiesFile(String file) {
            Properties props = new Properties();
            try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(file)) {
                if (is != null) {
                    props.load(is);
                } else {
                   //Try loading from this class classloader instead, e.g. for OSGi environments.
                    try(InputStream is2 = this.getClass().getClassLoader().getResourceAsStream(file)) {
                        if (is2 != null) {
                            props.load(is2);
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Can't parse file", e);
            }
            return props;
        }

        public String getString(String key) {
            return propsCache.computeIfAbsent(key, k -> {
                String value = System.getProperty(k);
                if (value == null)
                    value = customProperties.getProperty(k);
                if (value == null)
                    value = defaultProperties.getProperty(k);
                return value;
            });
        }

        public String[] getStringArray(String key) {
            String s = getString(key);
            String[] rawArray = s.split(",");
            String[] array = new String[rawArray.length];
            for (int i = 0; i < rawArray.length; i++)
                array[i] = rawArray[i].trim();
            return array;
        }

        public int getInt(String key) {
            return Integer.parseInt(getString(key));
        }

        public long getLong(String key) {
            return Long.parseLong(getString(key));
        }

        public Integer getInteger(String key) {
            String s = getString(key);
            return s != null ? Integer.valueOf(s) : null;
        }

        public boolean getBoolean(String key) {
            return Boolean.parseBoolean(getString(key));
        }
    }
}
