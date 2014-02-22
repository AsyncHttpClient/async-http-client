package org.asynchttpclient;

import java.util.Set;

public interface AsyncHttpClientRegistry {

    /**
     * Returns back the AsyncHttpClient associated with this name
     * 
     * @param clientName
     * @return
     */
    public AsyncHttpClient get(String clientName);

    /**
     * Registers this instance of AsyncHttpClient with this name and returns
     * back a null if the instance never existed but will return back the
     * previous instance if there was another instance registered with the same
     * name.
     * 
     * @param name
     * @param ahc
     * @return
     */
    public AsyncHttpClient register(String name, AsyncHttpClient ahc);

    /**
     * Will register only if an instance with this name doesn't exist and if it
     * does exist will not replace this instance and will return false
     * 
     * @param name
     * @param ahc
     * @return
     */

    public boolean registerIfNew(String name, AsyncHttpClient ahc);

    /**
     * Remove the instance associate with this name
     * 
     * @param name
     * @return
     */

    public boolean unRegister(String name);

    /**
     * Returns back all registered names
     * 
     * @return
     */
    public Set<String> getAllRegisteredNames();

    /**
     * Removes all instances from this registry.
     */
    public void clearAllInstances();

}