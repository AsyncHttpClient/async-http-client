package org.asynchttpclient.util;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class AsyncPropertiesHelper {
    
    public static final String ASYNC_HTTP_CLIENT_IMPL_PROPERTIES_FILE = "ahc.properties";
    public static final String DEFAULTAHC_PROPERTIES = "ahc-default.properties";
    
    public static Config getAsyncHttpClientConfig(){
        return ConfigFactory.load(ASYNC_HTTP_CLIENT_IMPL_PROPERTIES_FILE)
                .withFallback(ConfigFactory.load(DEFAULTAHC_PROPERTIES));
    }
    
    /**
     * This method invalidates the property caches. So if a system property has been changed and the
     * effect of this change is to be seen then call reloadProperties() and then getAsyncHttpClientConfig() 
     * to get the new property values.
     */
    public static void reloadProperties(){
        ConfigFactory.invalidateCaches();
    }

}
