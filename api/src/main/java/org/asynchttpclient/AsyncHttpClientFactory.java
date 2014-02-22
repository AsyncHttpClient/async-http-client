package org.asynchttpclient;

import java.lang.reflect.Constructor;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.asynchttpclient.util.AsyncImplHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The AsyncHttpClientFactory returns back an instance of AsyncHttpClient. The
 * actual instance is determined by the system property
 * 'org.async.http.client.impl'. If the system property doesn't exist then it
 * checks for a property file 'asynchttpclient.properties' and looks for a
 * property 'org.async.http.client.impl' in there. If it finds it then returns
 * an instance of that class. If there is an exception while reading the
 * properties file or system property it throws a RuntimeException
 * AsyncHttpClientImplException. If any of the constructors of the instance
 * throws an exception it thows a AsyncHttpClientImplException.
 * 
 * @author sasurendran
 * 
 */
public class AsyncHttpClientFactory {

    private static Class<AsyncHttpClient> asyncHttpClientImplClass = null;
    private static volatile boolean instantiated = false;
    public static Logger logger = LoggerFactory.getLogger(AsyncHttpClientFactory.class.getName());
    private static Lock lock = new ReentrantLock();

    public static AsyncHttpClient getAsyncHttpClient() {

        try {
            if (attemptInstantiation())
                return (AsyncHttpClient) asyncHttpClientImplClass.newInstance();
        } catch (InstantiationException e) {
            throw new AsyncHttpClientImplException("Unable to find the class specified by system property : "
                    + AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY
                    + ". Returning the default instance of IAsyncHttpClient : " + AsyncHttpClientImpl.class.getName(),
                    e);
        } catch (IllegalAccessException e) {
            throw new AsyncHttpClientImplException("Unable to find the class specified by system property : "
                    + AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY
                    + ". Returning the default instance of IAsyncHttpClient : " + AsyncHttpClientImpl.class.getName(),
                    e);
        }
        return new AsyncHttpClientImpl();

    }

    public static AsyncHttpClient getAsyncHttpClient(AsyncHttpProvider provider) {
        if (attemptInstantiation()) {
            try {
                Constructor<AsyncHttpClient> constructor = asyncHttpClientImplClass
                        .getConstructor(AsyncHttpProvider.class);
                return constructor.newInstance(provider);
            } catch (Exception e) {
                throw new AsyncHttpClientImplException(
                        "Unable to find the instantiate the class specified by system property : "
                                + AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY
                                + "(AsyncHttpProvider) due to : " + e.getMessage()
                                + ". Returning the default instance of IAsyncHttpClient : "
                                + AsyncHttpClientImpl.class.getName(), e);
            }
        }
        return new AsyncHttpClientImpl(provider);
    }

    public static AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        if (attemptInstantiation()) {
            try {
                Constructor<AsyncHttpClient> constructor = asyncHttpClientImplClass
                        .getConstructor(AsyncHttpClientConfig.class);
                return constructor.newInstance(config);
            } catch (Exception e) {
                throw new AsyncHttpClientImplException(
                        "Unable to find the instantiate the class specified by system property : "
                                + AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY
                                + "(AsyncHttpProvider) due to : " + e.getMessage()
                                + ". Returning the default instance of IAsyncHttpClient : "
                                + AsyncHttpClientImpl.class.getName(), e);
            }
        }
        return new AsyncHttpClientImpl(config);
    }

    public static AsyncHttpClient getAsyncHttpClient(AsyncHttpProvider provider, AsyncHttpClientConfig config) {
        if (attemptInstantiation()) {
            try {
                Constructor<AsyncHttpClient> constructor = asyncHttpClientImplClass.getConstructor(
                        AsyncHttpProvider.class, AsyncHttpClientConfig.class);
                return constructor.newInstance(provider, config);
            } catch (Exception e) {
                throw new AsyncHttpClientImplException(
                        "Unable to find the instantiate the class specified by system property : "
                                + AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY
                                + "(AsyncHttpProvider) due to : " + e.getMessage()
                                + ". Returning the default instance of IAsyncHttpClient : "
                                + AsyncHttpClientImpl.class.getName(), e);
            }
        }
        return new AsyncHttpClientImpl(provider, config);
    }

    public static AsyncHttpClient getAsyncHttpClient(String providerClass, AsyncHttpClientConfig config) {
        if (attemptInstantiation()) {
            try {
                Constructor<AsyncHttpClient> constructor = asyncHttpClientImplClass.getConstructor(String.class,
                        AsyncHttpClientConfig.class);
                return constructor.newInstance(providerClass, config);
            } catch (Exception e) {
                throw new AsyncHttpClientImplException(
                        "Unable to find the instantiate the class specified by system property : "
                                + AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY
                                + "(AsyncHttpProvider) due to : " + e.getMessage()
                                + ". Returning the default instance of IAsyncHttpClient : "
                                + AsyncHttpClientImpl.class.getName(), e);
            }
        }
        return new AsyncHttpClientImpl(providerClass, config);
    }

    private static boolean attemptInstantiation() {
        if (instantiated == false) {
            lock.lock();
            try {
                if (instantiated == false) {
                    asyncHttpClientImplClass = AsyncImplHelper
                            .getAsyncImplClass(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY);
                    instantiated = true;
                }
            } finally {
                lock.unlock();
            }
        }
        return (asyncHttpClientImplClass != null);
    }
}
