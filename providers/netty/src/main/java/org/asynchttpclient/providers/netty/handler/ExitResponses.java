package org.asynchttpclient.providers.netty.handler;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpResponse;

import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;

public class ExitResponses {
	
	private Channel channel;
    private NettyResponseFuture<?> future;
    private HttpResponse response;
    private Request request;
    private int statusCode;
    private Realm realm;
    private ProxyServer proxyServer;
    
    public ExitResponses() {
    	
    }
    
    public Channel getChannel() {
    	return channel;
    }
    
    public void setChannel(Channel channel) {
    	this.channel = channel;
    }
}
