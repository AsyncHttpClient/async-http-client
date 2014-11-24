package org.asynchttpclient.providers.netty.handler.exit_types;

import static org.asynchttpclient.util.AsyncHttpProviderUtils.getDefaultPort;

import java.io.IOException;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;

import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.providers.netty.channel.ChannelManager;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.handler.ExitResponses;
import org.asynchttpclient.providers.netty.handler.HttpProtocol;
import org.asynchttpclient.providers.netty.handler.IExitHandler;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExitAfterHandlingConnect implements IExitHandler {
	
	protected final Logger logger = LoggerFactory.getLogger(getClass());
	
	@Override
	public boolean exitHandler(HttpProtocol hp, ExitResponses exitResponses)
			throws IOException, Exception {
		
		final Channel channel = exitResponses.getChannel();
        final NettyResponseFuture<?> future = exitResponses.getFuture();
        final Request request = exitResponses.getRequest();
        ProxyServer proxyServer = exitResponses.getProxyServer();
        int statusCode = exitResponses.getStatusCode();
        HttpRequest httpRequest = exitResponses.getHttpRequest();
        ChannelManager channelManager = exitResponses.getChannelManager();
        NettyRequestSender requestSender = exitResponses.getRequestSender();
        
		if (statusCode == OK.code() && httpRequest.getMethod() == HttpMethod.CONNECT) {

            if (future.isKeepAlive())
                future.attachChannel(channel, true);

            try {
                Uri requestUri = request.getUri();
                String scheme = requestUri.getScheme();
                String host = requestUri.getHost();
                int port = getDefaultPort(requestUri);

                logger.debug("Connecting to proxy {} for scheme {}", proxyServer, scheme);
                channelManager.upgradeProtocol(channel.pipeline(), scheme, host, port);

            } catch (Throwable ex) {
                requestSender.abort(channel, future, ex);
            }

            future.setReuseChannel(true);
            future.setConnectAllowed(false);
            requestSender.sendNextRequest(new RequestBuilder(future.getRequest()).build(), future);
            System.out.println("CONNECT TRUE");
            return true;
        }
		System.out.println("CONNECT FALSE");
        return false;
    }


}
