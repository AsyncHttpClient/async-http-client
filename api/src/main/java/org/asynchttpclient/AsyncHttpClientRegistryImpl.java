package org.asynchttpclient;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.asynchttpclient.util.AsyncImplHelper;

public class AsyncHttpClientRegistryImpl implements AsyncHttpClientRegistry {
	
	private static ConcurrentHashMap<String,AsyncHttpClient> asyncHttpClientMap = new ConcurrentHashMap<String,AsyncHttpClient>();
	private static volatile AsyncHttpClientRegistry _instance;
	private static Lock lock = new ReentrantLock();
	
	public static AsyncHttpClientRegistry getInstance(){
		if(_instance == null){
			lock.lock();
			try {
				if(_instance == null){
					Class asyncHttpClientRegistryImplClass = AsyncImplHelper.getAsyncImplClass(AsyncImplHelper.ASYNC_HTTP_CLIENT_REGISTRY_SYSTEM_PROPERTY);	
					if(asyncHttpClientRegistryImplClass != null)
						_instance = (AsyncHttpClientRegistry) asyncHttpClientRegistryImplClass.newInstance();
					else
						_instance = new AsyncHttpClientRegistryImpl();
				}
			} catch (InstantiationException e) {
				throw new AsyncHttpClientImplException("Couldn't instantiate AsyncHttpClientRegistry : " + e.getMessage(),e);
			} catch (IllegalAccessException e) {
				throw new AsyncHttpClientImplException("Couldn't instantiate AsyncHttpClientRegistry : " + e.getMessage(),e);
			}finally{
				lock.unlock();
			}
		}
		return _instance;
	}
	
	/* (non-Javadoc)
	 * @see org.asynchttpclient.IAsyncHttpClientRegistry#get(java.lang.String)
	 */
	@Override
	public AsyncHttpClient get(String clientName){
		return asyncHttpClientMap.get(clientName);
	}
	
	/* (non-Javadoc)
	 * @see org.asynchttpclient.IAsyncHttpClientRegistry#register(java.lang.String, org.asynchttpclient.AsyncHttpClient)
	 */
	@Override
	public AsyncHttpClient register(String name,AsyncHttpClient ahc){
		return asyncHttpClientMap.put(name, ahc);
	}
	
	/* (non-Javadoc)
	 * @see org.asynchttpclient.IAsyncHttpClientRegistry#registerIfNew(java.lang.String, org.asynchttpclient.AsyncHttpClient)
	 */
	@Override
	public boolean registerIfNew(String name,AsyncHttpClient ahc){
		AsyncHttpClient value = asyncHttpClientMap.putIfAbsent(name, ahc);
		if(value == null)
			return true;
		else
			return false;
	}
	
	/* (non-Javadoc)
	 * @see org.asynchttpclient.IAsyncHttpClientRegistry#unRegister(java.lang.String)
	 */
	@Override
	public boolean unRegister(String name){
		if(asyncHttpClientMap.remove(name)==null)
			return false;
		else
			return true;
	}
	
	/* (non-Javadoc)
	 * @see org.asynchttpclient.IAsyncHttpClientRegistry#getAllRegisteredNames()
	 */
	@Override
	public Set<String> getAllRegisteredNames(){
		return asyncHttpClientMap.keySet();
	}
	
	/* (non-Javadoc)
	 * @see org.asynchttpclient.IAsyncHttpClientRegistry#clearAllInstances()
	 */
	@Override
	public void clearAllInstances(){
		asyncHttpClientMap.clear();
	}

}
