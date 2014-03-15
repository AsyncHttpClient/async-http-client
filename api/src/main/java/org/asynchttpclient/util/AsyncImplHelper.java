package org.asynchttpclient.util;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientFactory;
import org.asynchttpclient.AsyncHttpClientImplException;

import java.io.IOException;
import java.io.InputStream;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Properties;

public class AsyncImplHelper {

    private static String getSystemProperty(final String systemProperty) {
        return AccessController.doPrivileged(new PrivilegedAction<String>() {
            public String run() {
                return System.getProperty(systemProperty);
            }
        });
    }

    /*
     * Returns the class specified by either a system property or a properties
     * file as the class to instantiated for the AsyncHttpClient. Returns null
     * if property is not found and throws an AsyncHttpClientImplException if
     * the specified class couldn't be created.
     */
    public static Class<AsyncHttpClient> getAsyncImplClass(String propertyName) {
        String asyncHttpClientImplClassName = getSystemProperty(propertyName);
        if (asyncHttpClientImplClassName == null) {
            Properties properties = AsyncImplHelper.getAsyncImplProperties();
            if (properties != null)
                asyncHttpClientImplClassName = properties.getProperty(propertyName);
        }

        if (asyncHttpClientImplClassName == null)
            return null;

        Class<AsyncHttpClient> asyncHttpClientImplClass = AsyncImplHelper.getClass(asyncHttpClientImplClassName);
        return asyncHttpClientImplClass;
    }

    private static Properties getAsyncImplProperties() {
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<Properties>() {
                public Properties run() throws IOException {
                    InputStream stream = null;
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    if (cl != null)
                        stream = cl.getResourceAsStream(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_PROPERTIES_FILE);
                    if (stream == null)
                        stream = ClassLoader.getSystemClassLoader().getResourceAsStream(
                                AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_PROPERTIES_FILE);
                    if (stream != null) {
                        Properties properties = new Properties();
                        properties.load(stream);
                        return properties;
                    }
                    return null;
                }
            });
        } catch (PrivilegedActionException e) {
            throw new AsyncHttpClientImplException("Unable to read properties file because of exception : "
                    + e.getMessage(), e);
        }
    }

    private static Class<AsyncHttpClient> getClass(final String asyncImplClassName) {
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<Class<AsyncHttpClient>>() {
                public Class run() throws ClassNotFoundException {
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    if (cl != null)
                        try {
                            return cl.loadClass(asyncImplClassName);
                        } catch (ClassNotFoundException e) {
                            AsyncHttpClientFactory.logger.info("Couldn't find class : " + asyncImplClassName
                                    + " in thread context classpath " + "checking system class path next",e);
                        }

                    cl = ClassLoader.getSystemClassLoader();
                    return cl.loadClass(asyncImplClassName);
                }
            });
        } catch (PrivilegedActionException e) {
            throw new AsyncHttpClientImplException("Class : " + asyncImplClassName + " couldn't be found in "
                    + " the classpath due to : " + e.getMessage(), e);
        }
    }

    public static final String ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY = "org.async.http.client.impl";
    public static final String ASYNC_HTTP_CLIENT_REGISTRY_SYSTEM_PROPERTY = "org.async.http.client.registry.impl";
    public static final String ASYNC_HTTP_CLIENT_IMPL_PROPERTIES_FILE = "asynchttpclient.properties";

}
