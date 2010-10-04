package com.ning.http.client.providers.jdk;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ConnectionsPool;

import java.net.URLConnection;


public class JDKConnectionsPool implements ConnectionsPool<String, URLConnection> {


    public JDKConnectionsPool(AsyncHttpClientConfig config) {
    }

    public boolean addConnection(String uri, URLConnection connection) {
        return false;  
    }

    public URLConnection getConnection(String uri) {
        return null;  
    }

    public URLConnection removeConnection(String uri) {
        return null;  
    }

    public boolean removeAllConnections(URLConnection connection) {
        return false;  
    }

    public boolean canCacheConnection() {
       return false;
    }

    public void destroy() {        
    }
}
