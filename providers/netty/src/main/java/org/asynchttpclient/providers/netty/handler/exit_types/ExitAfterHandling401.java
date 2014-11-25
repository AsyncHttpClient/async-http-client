package org.asynchttpclient.providers.netty.handler.exit_types;

import static org.asynchttpclient.providers.netty.util.HttpUtils.getNTLM;

import java.io.IOException;
import java.util.List;

import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpHeaders;

import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.providers.netty.Callback;
import org.asynchttpclient.providers.netty.channel.ChannelManager;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.handler.ExitResponses;
import org.asynchttpclient.providers.netty.handler.HttpProtocol;
import org.asynchttpclient.providers.netty.handler.IExitHandler;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExitAfterHandling401 implements IExitHandler {

	protected final Logger logger = LoggerFactory.getLogger(getClass());
	
	@Override
	public boolean exitHandler(HttpProtocol hp, ExitResponses exitResponses)
			throws IOException, Exception {
		final Channel channel = exitResponses.getChannel();
        final NettyResponseFuture<?> future = exitResponses.getFuture();
        HttpResponse response = exitResponses.getResponse();
        final Request request = exitResponses.getRequest();
        int statusCode = exitResponses.getStatusCode();
        Realm realm = exitResponses.getRealm();
        ProxyServer proxyServer = exitResponses.getProxyServer();
        final ChannelManager channelManager = exitResponses.getChannelManager();
        final NettyRequestSender requestSender = exitResponses.getRequestSender();
        
        if (statusCode == UNAUTHORIZED.code() && realm != null && !future.getAndSetAuth(true)) {

            List<String> wwwAuthHeaders = response.headers().getAll(HttpHeaders.Names.WWW_AUTHENTICATE);

            if (!wwwAuthHeaders.isEmpty()) {
                future.setState(NettyResponseFuture.STATE.NEW);
                Realm newRealm = null;
                boolean negociate = wwwAuthHeaders.contains("Negotiate");
                String ntlmAuthenticate = getNTLM(wwwAuthHeaders);
                if (!wwwAuthHeaders.contains("Kerberos") && ntlmAuthenticate != null) {
                    // NTLM
                    newRealm = hp.ntlmChallenge(ntlmAuthenticate, request, proxyServer, request.getHeaders(), realm, future, false);

                    // don't forget to reuse channel: NTLM authenticates a connection
                    future.setReuseChannel(true);

                } else if (negociate) {
                    newRealm = hp.kerberosChallenge(channel, wwwAuthHeaders, request, proxyServer, request.getHeaders(), realm, future, false);
                    // SPNEGO KERBEROS
                    if (newRealm == null)
                        return true;
                    else
                        // don't forget to reuse channel: KERBEROS authenticates a connection
                        future.setReuseChannel(true);

                } else {
                    newRealm = new Realm.RealmBuilder()//
                            .clone(realm)//
                            .setScheme(realm.getAuthScheme())//
                            .setUri(request.getUri())//
                            .setMethodName(request.getMethod())//
                            .setUsePreemptiveAuth(true)//
                            .parseWWWAuthenticateHeader(wwwAuthHeaders.get(0))//
                            .build();
                }

                Realm nr = newRealm;
                final Request nextRequest = new RequestBuilder(future.getRequest()).setHeaders(request.getHeaders()).setRealm(nr).build();

                logger.debug("Sending authentication to {}", request.getUri());
                Callback callback = new Callback(future) {
                    public void call() throws IOException {
                        channelManager.drainChannel(channel, future);
                        requestSender.sendNextRequest(nextRequest, future);
                    }
                };

                if (future.isKeepAlive() && HttpHeaders.isTransferEncodingChunked(response))
                    // We must make sure there is no bytes left
                    // before executing the next request.
                    Channels.setAttribute(channel, callback);
                else
                    // call might crash with an IOException
                    callback.call();

                return true;
            }
        }

		return false;
	}

}
