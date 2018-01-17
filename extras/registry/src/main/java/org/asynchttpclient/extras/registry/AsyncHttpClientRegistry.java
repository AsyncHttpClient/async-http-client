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

public interface AsyncHttpClientRegistry {

  /**
   * Returns back the AsyncHttpClient associated with this name
   *
   * @param name the name of the client instance in the registry
   * @return the client
   */
  AsyncHttpClient get(String name);

  /**
   * Registers this instance of AsyncHttpClient with this name and returns
   * back a null if an instance with the same name never existed but will return back the
   * previous instance if there was another instance registered with the same
   * name and has been replaced by this one.
   *
   * @param name   the name of the client instance in the registry
   * @param client the client instance
   * @return the previous instance
   */
  AsyncHttpClient addOrReplace(String name, AsyncHttpClient client);

  /**
   * Will register only if an instance with this name doesn't exist and if it
   * does exist will not replace this instance and will return false. Use it in the
   * following way:
   * <blockquote><pre>
   *      AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient();
   *      if(!AsyncHttpClientRegistryImpl.getInstance().registerIfNew(“MyAHC”,ahc)){
   *          //An instance with this name is already registered so close ahc
   *          ahc.close();
   *          //and do necessary cleanup
   *      }
   * </pre></blockquote>
   *
   * @param name   the name of the client instance in the registry
   * @param client the client instance
   * @return true is the client was indeed registered
   */

  boolean registerIfNew(String name, AsyncHttpClient client);

  /**
   * Remove the instance associate with this name
   *
   * @param name the name of the client instance in the registry
   * @return true is the client was indeed unregistered
   */

  boolean unregister(String name);

  /**
   * @return all registered names
   */

  Set<String> getAllRegisteredNames();

  /**
   * Removes all instances from this registry.
   */

  void clearAllInstances();
}
