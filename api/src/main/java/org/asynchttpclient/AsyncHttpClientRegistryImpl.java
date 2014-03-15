package org.asynchttpclient;

import org.asynchttpclient.util.AsyncImplHelper;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class AsyncHttpClientRegistryImpl implements AsyncHttpClientRegistry {

    private static ConcurrentMap<String, AsyncHttpClient> asyncHttpClientMap = new ConcurrentHashMap<String, AsyncHttpClient>();
    private static volatile AsyncHttpClientRegistry _instance;
    private static Lock lock = new ReentrantLock();

    /**
     * Returns a singleton instance of AsyncHttpClientRegistry
     * @return
     */
    public static AsyncHttpClientRegistry getInstance() {
        if (_instance == null) {
            lock.lock();
            try {
                if (_instance == null) {
                    Class asyncHttpClientRegistryImplClass = AsyncImplHelper
                            .getAsyncImplClass(AsyncImplHelper.ASYNC_HTTP_CLIENT_REGISTRY_SYSTEM_PROPERTY);
                    if (asyncHttpClientRegistryImplClass != null)
                        _instance = (AsyncHttpClientRegistry) asyncHttpClientRegistryImplClass.newInstance();
                    else
                        _instance = new AsyncHttpClientRegistryImpl();
                }
            } catch (InstantiationException e) {
                throw new AsyncHttpClientImplException("Couldn't instantiate AsyncHttpClientRegistry : "
                        + e.getMessage(), e);
            } catch (IllegalAccessException e) {
                throw new AsyncHttpClientImplException("Couldn't instantiate AsyncHttpClientRegistry : "
                        + e.getMessage(), e);
            } finally {
                lock.unlock();
            }
        }
        return _instance;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClientRegistry#get(java.lang.String)
     */
    @Override
    public AsyncHttpClient get(String clientName) {
        return asyncHttpClientMap.get(clientName);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.asynchttpclient.IAsyncHttpClientRegistry#register(java.lang.String,
     * org.asynchttpclient.AsyncHttpClient)
     */
    @Override
    public AsyncHttpClient addOrReplace(String name, AsyncHttpClient ahc) {
        return asyncHttpClientMap.put(name, ahc);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.asynchttpclient.IAsyncHttpClientRegistry#registerIfNew(java.lang.
     * String, org.asynchttpclient.AsyncHttpClient)
     */
    @Override
    public boolean registerIfNew(String name, AsyncHttpClient ahc) {
        return asyncHttpClientMap.putIfAbsent(name, ahc)==null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.asynchttpclient.IAsyncHttpClientRegistry#unRegister(java.lang.String)
     */
    @Override
    public boolean unRegister(String name) {
        return asyncHttpClientMap.remove(name) != null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClientRegistry#getAllRegisteredNames()
     */
    @Override
    public Set<String> getAllRegisteredNames() {
        return asyncHttpClientMap.keySet();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.asynchttpclient.IAsyncHttpClientRegistry#clearAllInstances()
     */
    @Override
    public void clearAllInstances() {
        asyncHttpClientMap.clear();
    }

}
