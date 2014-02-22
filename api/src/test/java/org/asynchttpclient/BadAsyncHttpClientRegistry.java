package org.asynchttpclient;

public class BadAsyncHttpClientRegistry extends AsyncHttpClientRegistryImpl {
	
	private BadAsyncHttpClientRegistry(){
		throw new RuntimeException("I am bad");
	}

}
