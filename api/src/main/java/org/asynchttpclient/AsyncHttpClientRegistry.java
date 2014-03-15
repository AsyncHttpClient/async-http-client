package org.asynchttpclient;

import java.util.Set;

public interface AsyncHttpClientRegistry {

    /**
     * Returns back the AsyncHttpClient associated with this name
     * 
     * @param clientName
     * @return
     */
    AsyncHttpClient get(String clientName);

    /**
     * Registers this instance of AsyncHttpClient with this name and returns
     * back a null if an instance with the same name never existed but will return back the
     * previous instance if there was another instance registered with the same
     * name and has been replaced by this one.
     * 
     * @param name
     * @param ahc
     * @return
     */
    AsyncHttpClient addOrReplace(String name, AsyncHttpClient ahc);

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
     * @param name
     * @param ahc
     * @return
     */

    boolean registerIfNew(String name, AsyncHttpClient ahc);

    /**
     * Remove the instance associate with this name
     * 
     * @param name
     * @return
     */

    boolean unRegister(String name);

    /**
     * Returns back all registered names
     * 
     * @return
     */
    
    Set<String> getAllRegisteredNames();

    /**
     * Removes all instances from this registry.
     */
    
    void clearAllInstances();

}