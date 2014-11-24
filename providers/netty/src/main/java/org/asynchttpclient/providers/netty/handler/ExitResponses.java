package org.asynchttpclient.providers.netty.handler;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpRequest;

import org.asynchttpclient.providers.netty.channel.ChannelManager;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.asynchttpclient.providers.netty.response.NettyResponseHeaders;
import org.asynchttpclient.providers.netty.response.NettyResponseStatus;

/*
 * Class that contains all parameters needed for exit responses.
 */
public class ExitResponses {
	
	private Channel channel = null;
    private NettyResponseFuture<?> future = null;
    private AsyncHandler<?> handler = null;
    private NettyResponseStatus status = null;
    private NettyResponseHeaders responseHeaders = null;
    private HttpResponse response = null;
    private Request request = null;
    private int statusCode = -1;
    private Realm realm = null;
    private ProxyServer proxyServer = null;
    private HttpRequest httpRequest = null;
    private ChannelManager channelManager = null;
    private NettyRequestSender requestSender = null;
    
    public ExitResponses(Channel channel, NettyResponseFuture<?> future, 
    		AsyncHandler<?>handler, NettyResponseStatus status, NettyResponseHeaders responseHeaders,
    		HttpResponse response, Request request, int statusCode, Realm realm, 
    		ProxyServer proxyServer, HttpRequest httpRequest,
    		ChannelManager channelManager, NettyRequestSender requestSender) {
    	
    	this.channel = channel;
    	this.future = future;
    	this.handler = handler;
    	this.status = status;
    	this.responseHeaders = responseHeaders;
    	this.response = response;
    	this.request = request;
    	this.statusCode = statusCode;
    	this.realm = realm;
    	this.proxyServer = proxyServer;
    	this.httpRequest = httpRequest;
    	this.channelManager = channelManager;
    	this.requestSender = requestSender;
    }
    
    public Channel getChannel() {
    	return this.channel;
    }
    
    public NettyResponseFuture<?> getFuture() {
    	return this.future;
    }

    public AsyncHandler<?> getHandler() {
    	return this.handler;
    }
    
    public NettyResponseStatus getStatus() {
    	return this.status;
    }
    
    public NettyResponseHeaders getResponseHeaders() {
    	return this.responseHeaders;
    }
    
    public HttpResponse getResponse() {
    	return this.response;
    }
    
    public Request getRequest() {
    	return this.request;
    }
    
    public int getStatusCode() {
    	return this.statusCode;
    }
    
    public Realm getRealm() {
    	return this.realm;
    }
    
    public ProxyServer getProxyServer() {
    	return this.proxyServer;
    }
    
    public HttpRequest getHttpRequest() {
    	return this.httpRequest;
    }
    
    public ChannelManager getChannelManager() {
    	return this.channelManager;
    }
    
    public NettyRequestSender getRequestSender() {
    	return this.requestSender;
    }
}
