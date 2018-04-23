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
   * This method invalidates the property caches. So if a system property has been changed and the effect of this change is to be seen then call reloadProperties() and then
   * getAsyncHttpClientConfig() to get the new property values.
   */
  public static void reloadProperties() {
    if (config != null)
      config.reload();
  }

  public static class Config {

    public static final String DEFAULT_AHC_PROPERTIES = "ahc-default.properties";
    public static final String CUSTOM_AHC_PROPERTIES = "ahc.properties";

    private final ConcurrentHashMap<String, String> propsCache = new ConcurrentHashMap<>();
    private final Properties defaultProperties = parsePropertiesFile(DEFAULT_AHC_PROPERTIES, true);
    private volatile Properties customProperties = parsePropertiesFile(CUSTOM_AHC_PROPERTIES, false);

    public void reload() {
      customProperties = parsePropertiesFile(CUSTOM_AHC_PROPERTIES, false);
      propsCache.clear();
    }

    private Properties parsePropertiesFile(String file, boolean required) {
      Properties props = new Properties();

      InputStream is = getClass().getResourceAsStream(file);
      if (is != null) {
        try {
          props.load(is);
        } catch (IOException e) {
          throw new IllegalArgumentException("Can't parse config file " + file, e);
        }
      } else if (required) {
        throw new IllegalArgumentException("Can't locate config file " + file);
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
      s = s.trim();
      if (s.isEmpty()) {
        return null;
      }
      String[] rawArray = s.split(",");
      String[] array = new String[rawArray.length];
      for (int i = 0; i < rawArray.length; i++)
        array[i] = rawArray[i].trim();
      return array;
    }

    public int getInt(String key) {
      return Integer.parseInt(getString(key));
    }

    public boolean getBoolean(String key) {
      return Boolean.parseBoolean(getString(key));
    }
  }
}
