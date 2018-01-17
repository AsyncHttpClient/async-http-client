/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class AsyncHttpClientRegistryImpl implements AsyncHttpClientRegistry {

  private static ConcurrentMap<String, AsyncHttpClient> asyncHttpClientMap = new ConcurrentHashMap<>();
  private static volatile AsyncHttpClientRegistry _instance;
  private static Lock lock = new ReentrantLock();

  /**
   * Returns a singleton instance of AsyncHttpClientRegistry
   *
   * @return the current instance
   */
  public static AsyncHttpClientRegistry getInstance() {
    if (_instance == null) {
      lock.lock();
      try {
        if (_instance == null) {
          Class<?> asyncHttpClientRegistryImplClass = AsyncImplHelper
                  .getAsyncImplClass(AsyncImplHelper.ASYNC_HTTP_CLIENT_REGISTRY_SYSTEM_PROPERTY);
          if (asyncHttpClientRegistryImplClass != null)
            _instance = (AsyncHttpClientRegistry) asyncHttpClientRegistryImplClass.newInstance();
          else
            _instance = new AsyncHttpClientRegistryImpl();
        }
      } catch (InstantiationException | IllegalAccessException e) {
        throw new AsyncHttpClientImplException("Couldn't instantiate AsyncHttpClientRegistry : " + e.getMessage(), e);
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
    return asyncHttpClientMap.putIfAbsent(name, ahc) == null;
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.asynchttpclient.IAsyncHttpClientRegistry#unRegister(java.lang.String)
   */
  @Override
  public boolean unregister(String name) {
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
