package org.asynchttpclient.providers.netty.handler;

import org.asynchttpclient.providers.netty.handler.ExitResponses;

public interface IExitHandler {
	
	public boolean exitAfterHandling();
	public boolean exitHandler(ExitResponses exitResponses);
}
