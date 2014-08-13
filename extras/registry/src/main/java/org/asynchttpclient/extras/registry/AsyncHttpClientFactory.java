/*
 * Copyright (c) 2010-2014 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.extras.registry;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The AsyncHttpClientFactory returns back an instance of AsyncHttpClient. The
 * actual instance is determined by the system property
 * 'org.async.http.client.impl'. If the system property doesn't exist then it
 * checks for a property file 'asynchttpclient.properties' and looks for a
 * property 'org.async.http.client.impl' in there. If it finds it then returns
 * an instance of that class. If there is an exception while reading the
 * properties file or system property it throws a RuntimeException
 * AsyncHttpClientImplException. If any of the constructors of the instance
 * throws an exception it thows a AsyncHttpClientImplException. By default if
 * neither the system property or the property file exists then it will return
 * the default instance of {@link DefaultAsyncHttpClient}
 * 
 * @author sasurendran
 * 
 */
public class AsyncHttpClientFactory {

    private static Class<AsyncHttpClient> asyncHttpClientImplClass = null;
    private static volatile boolean instantiated = false;
    public static final Logger logger = LoggerFactory.getLogger(AsyncHttpClientFactory.class);
    private static Lock lock = new ReentrantLock();

    public static AsyncHttpClient getAsyncHttpClient() {

        try {
            if (attemptInstantiation())
                return (AsyncHttpClient) asyncHttpClientImplClass.newInstance();
        } catch (InstantiationException e) {
            throw new AsyncHttpClientImplException("Unable to create the class specified by system property : "
                    + AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, e);
        } catch (IllegalAccessException e) {
            throw new AsyncHttpClientImplException("Unable to find the class specified by system property : "
                    + AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, e);
        }
        return new DefaultAsyncHttpClient();
    }

    public static AsyncHttpClient getAsyncHttpClient(AsyncHttpProvider provider) {
        if (attemptInstantiation()) {
            try {
                Constructor<AsyncHttpClient> constructor = asyncHttpClientImplClass.getConstructor(AsyncHttpProvider.class);
                return constructor.newInstance(provider);
            } catch (Exception e) {
                throw new AsyncHttpClientImplException("Unable to find the instantiate the class specified by system property : "
                        + AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY + "(AsyncHttpProvider) due to : " + e.getMessage(), e);
            }
        }
        return new DefaultAsyncHttpClient(provider);
    }

    public static AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        if (attemptInstantiation()) {
            try {
                Constructor<AsyncHttpClient> constructor = asyncHttpClientImplClass.getConstructor(AsyncHttpClientConfig.class);
                return constructor.newInstance(config);
            } catch (Exception e) {
                throw new AsyncHttpClientImplException("Unable to find the instantiate the class specified by system property : "
                        + AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY + "(AsyncHttpProvider) due to : " + e.getMessage(), e);
            }
        }
        return new DefaultAsyncHttpClient(config);
    }

    public static AsyncHttpClient getAsyncHttpClient(AsyncHttpProvider provider, AsyncHttpClientConfig config) {
        if (attemptInstantiation()) {
            try {
                Constructor<AsyncHttpClient> constructor = asyncHttpClientImplClass.getConstructor(AsyncHttpProvider.class,
                        AsyncHttpClientConfig.class);
                return constructor.newInstance(provider, config);
            } catch (Exception e) {
                throw new AsyncHttpClientImplException("Unable to find the instantiate the class specified by system property : "
                        + AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY + "(AsyncHttpProvider) due to : " + e.getMessage(), e);
            }
        }
        return new DefaultAsyncHttpClient(provider, config);
    }

    public static AsyncHttpClient getAsyncHttpClient(String providerClass, AsyncHttpClientConfig config) {
        if (attemptInstantiation()) {
            try {
                Constructor<AsyncHttpClient> constructor = asyncHttpClientImplClass.getConstructor(String.class,
                        AsyncHttpClientConfig.class);
                return constructor.newInstance(providerClass, config);
            } catch (Exception e) {
                throw new AsyncHttpClientImplException("Unable to find the instantiate the class specified by system property : "
                        + AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY + "(AsyncHttpProvider) due to : " + e.getMessage(), e);
            }
        }
        return new DefaultAsyncHttpClient(providerClass, config);
    }

    private static boolean attemptInstantiation() {
        if (!instantiated) {
            lock.lock();
            try {
                if (!instantiated) {
                    asyncHttpClientImplClass = AsyncImplHelper.getAsyncImplClass(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY);
                    instantiated = true;
                }
            } finally {
                lock.unlock();
            }
        }
        return asyncHttpClientImplClass != null;
    }
}
