package org.asynchttpclient.providers.netty.handler.exit_types;

import java.io.IOException;

import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;

import io.netty.channel.Channel;

import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.handler.ExitResponses;
import org.asynchttpclient.providers.netty.handler.HttpProtocol;
import org.asynchttpclient.providers.netty.handler.IExitHandler;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;

public class ExitAfterHandling100 implements IExitHandler {

	@Override
	public boolean exitHandler(HttpProtocol hp, ExitResponses exitResponses)
			throws IOException, Exception {
		final Channel channel = exitResponses.getChannel();
		final NettyResponseFuture<?> future = exitResponses.getFuture();
		int statusCode = exitResponses.getStatusCode();
		final NettyRequestSender requestSender = exitResponses.getRequestSender();
		
		if (statusCode == CONTINUE.code()) {
            future.setHeadersAlreadyWrittenOnContinue(true);
            future.setDontWriteBodyBecauseExpectContinue(false);
            // FIXME why not reuse the channel?
            requestSender.writeRequest(future, channel);
            return true;
        }
		
		return false;
	}

}
