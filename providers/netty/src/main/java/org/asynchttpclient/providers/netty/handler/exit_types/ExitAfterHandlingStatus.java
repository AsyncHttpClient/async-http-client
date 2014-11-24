package org.asynchttpclient.providers.netty.handler.exit_types;

import java.io.IOException;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpHeaders;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.STATE;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.handler.ExitResponses;
import org.asynchttpclient.providers.netty.handler.HttpProtocol;
import org.asynchttpclient.providers.netty.handler.IExitHandler;
import org.asynchttpclient.providers.netty.response.NettyResponseStatus;

public class ExitAfterHandlingStatus implements IExitHandler {


	public boolean exitAfterHandling = false;
	
	@Override
	public boolean exitHandler(HttpProtocol hp, ExitResponses exitResponses) throws IOException, Exception {
		
		NettyResponseFuture<?> future = exitResponses.getFuture();
		Channel channel = exitResponses.getChannel();
		HttpResponse response = exitResponses.getResponse();
		AsyncHandler<?> handler = exitResponses.getHandler();
		NettyResponseStatus status = exitResponses.getStatus();
		System.out.println("HERE!");
		if (!future.getAndSetStatusReceived(true) && handler.onStatusReceived(status) != STATE.CONTINUE) {
            hp.finishUpdate(future, channel, HttpHeaders.isTransferEncodingChunked(response));
            return true;
        }
        return false;
	}

}
