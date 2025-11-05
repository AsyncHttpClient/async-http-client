package org.asynchttpclient.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper class for loading and managing AsyncHttpClient configuration properties.
 * Properties are loaded from multiple sources with the following precedence:
 * <ol>
 *   <li>System properties</li>
 *   <li>Custom properties file (ahc.properties)</li>
 *   <li>Default properties file (ahc-default.properties)</li>
 * </ol>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Get configuration
 * AsyncHttpClientConfigHelper.Config config = AsyncHttpClientConfigHelper.getAsyncHttpClientConfig();
 * String threadPoolName = config.getString("org.asynchttpclient.threadPoolName");
 *
 * // Reload properties after system property change
 * System.setProperty("org.asynchttpclient.maxConnections", "200");
 * AsyncHttpClientConfigHelper.reloadProperties();
 * }</pre>
 */
public class AsyncHttpClientConfigHelper {

  private static volatile Config config;

  /**
   * Returns the singleton configuration instance.
   * Creates a new instance if not already initialized.
   *
   * @return the configuration instance
   */
  public static Config getAsyncHttpClientConfig() {
    if (config == null) {
      config = new Config();
    }

    return config;
  }

  /**
   * Reloads all configuration properties from their sources.
   * This method invalidates the property caches, allowing changes to system properties
   * or configuration files to take effect. After calling this method, call
   * {@link #getAsyncHttpClientConfig()} to get the refreshed configuration.
   *
   * <p><b>Usage Examples:</b></p>
   * <pre>{@code
   * // Change a system property and reload
   * System.setProperty("org.asynchttpclient.connectTimeout", "5000");
   * AsyncHttpClientConfigHelper.reloadProperties();
   * }</pre>
   */
  public static void reloadProperties() {
    if (config != null)
      config.reload();
  }

  /**
   * Configuration holder that manages property loading and caching.
   * Properties are loaded from default and custom property files, with system properties
   * taking highest precedence.
   */
  public static class Config {

    public static final String DEFAULT_AHC_PROPERTIES = "ahc-default.properties";
    public static final String CUSTOM_AHC_PROPERTIES = "ahc.properties";

    private final ConcurrentHashMap<String, String> propsCache = new ConcurrentHashMap<>();
    private final Properties defaultProperties = parsePropertiesFile(DEFAULT_AHC_PROPERTIES, true);
    private volatile Properties customProperties = parsePropertiesFile(CUSTOM_AHC_PROPERTIES, false);

    /**
     * Reloads custom properties and clears the property cache.
     */
    public void reload() {
      customProperties = parsePropertiesFile(CUSTOM_AHC_PROPERTIES, false);
      propsCache.clear();
    }

    /**
     * Parses a properties file from the classpath.
     *
     * @param file the name of the properties file
     * @param required {@code true} if the file must exist, {@code false} if it's optional
     * @return the loaded properties
     * @throws IllegalArgumentException if the file is required but not found, or if parsing fails
     */
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

    /**
     * Returns a string property value.
     * Checks system properties first, then custom properties, then default properties.
     *
     * @param key the property key
     * @return the property value, or {@code null} if not found
     */
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

    /**
     * Returns a string array property value.
     * The property value should be a comma-separated list.
     *
     * @param key the property key
     * @return an array of trimmed string values, or {@code null} if the property is empty
     */
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

    /**
     * Returns an integer property value.
     *
     * @param key the property key
     * @return the property value as an integer
     * @throws NumberFormatException if the property value is not a valid integer
     */
    public int getInt(String key) {
      return Integer.parseInt(getString(key));
    }

    /**
     * Returns a boolean property value.
     *
     * @param key the property key
     * @return the property value as a boolean
     */
    public boolean getBoolean(String key) {
      return Boolean.parseBoolean(getString(key));
    }
  }
}
