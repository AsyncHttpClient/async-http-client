package org.asynchttpclient;

import java.io.IOException;

import org.asynchttpclient.AsyncHttpClientImpl.BoundRequestBuilder;

public class TestAsyncHttpClient implements AsyncHttpClient{
	
	public TestAsyncHttpClient() {
	        
	    }
    public TestAsyncHttpClient(AsyncHttpProvider provider) {
        
    }
    public TestAsyncHttpClient(AsyncHttpClientConfig config) {
        
    }
    public TestAsyncHttpClient(String providerClass, AsyncHttpClientConfig config) {
        
    }
    
    public TestAsyncHttpClient(AsyncHttpProvider httpProvider, AsyncHttpClientConfig config) {
    }

	@Override
	public AsyncHttpProvider getProvider() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void close() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void closeAsynchronously() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean isClosed() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public AsyncHttpClientConfig getConfig() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AsyncHttpClient setSignatureCalculator(
			SignatureCalculator signatureCalculator) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BoundRequestBuilder prepareGet(String url) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BoundRequestBuilder prepareConnect(String url) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BoundRequestBuilder prepareOptions(String url) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BoundRequestBuilder prepareHead(String url) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BoundRequestBuilder preparePost(String url) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BoundRequestBuilder preparePut(String url) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BoundRequestBuilder prepareDelete(String url) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BoundRequestBuilder preparePatch(String url) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BoundRequestBuilder prepareTrace(String url) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BoundRequestBuilder prepareRequest(Request request) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <T> ListenableFuture<T> executeRequest(Request request,
			AsyncHandler<T> handler) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ListenableFuture<Response> executeRequest(Request request)
			throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

}
