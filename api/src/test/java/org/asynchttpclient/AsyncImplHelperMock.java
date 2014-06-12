package org.asynchttpclient;

import java.util.Properties;

import mockit.Mock;
import mockit.MockUp;

import org.asynchttpclient.util.AsyncImplHelper;

public class AsyncImplHelperMock extends MockUp<AsyncImplHelper> {
	
	private static Properties properties;
	
	public AsyncImplHelperMock(Properties properties){
		this.properties=properties;
	}
	
	@Mock
	public Properties getAsyncImplProperties() {
		return properties;
	}

}
