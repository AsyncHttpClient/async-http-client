package org.asynchttpclient.providers.netty.handler;

import java.io.IOException;

import org.asynchttpclient.providers.netty.handler.ExitResponses;

public interface IExitHandler {
	
	public boolean exitAfterHandling = false;
	public boolean exitHandler(HttpProtocol hp, ExitResponses exitResponses) throws IOException, Exception;
}
