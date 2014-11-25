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
import org.asynchttpclient.providers.netty.response.NettyResponseHeaders;

public class ExitAfterHandlingHeaders implements IExitHandler {

	@Override
	public boolean exitHandler(HttpProtocol hp, ExitResponses exitResponses)
			throws IOException, Exception {
		Channel channel = exitResponses.getChannel();
		NettyResponseFuture<?> future = exitResponses.getFuture();
		HttpResponse response = exitResponses.getResponse();
		AsyncHandler<?> handler = exitResponses.getHandler();
		NettyResponseHeaders responseHeaders = exitResponses.getResponseHeaders();
		
		if (!response.headers().isEmpty() && handler.onHeadersReceived(responseHeaders) != STATE.CONTINUE) {
            hp.finishUpdate(future, channel, HttpHeaders.isTransferEncodingChunked(response));
            return true;
        }
		
		return false;
	}

}
