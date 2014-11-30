package org.asynchttpclient.providers.netty.handler.exit_types;

import static org.asynchttpclient.providers.netty.util.HttpUtils.getNTLM;

import java.io.IOException;
import java.util.List;
import java.lang.reflect.Method;

import static io.netty.handler.codec.http.HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;

import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.handler.ExitResponses;
import org.asynchttpclient.providers.netty.handler.HttpProtocol;
import org.asynchttpclient.providers.netty.handler.IExitHandler;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExitAfterHandling407 implements IExitHandler {

	protected final Logger logger = LoggerFactory.getLogger(getClass());
	
	@Override
	public boolean exitHandler(HttpProtocol hp, ExitResponses exitResponses)
			throws IOException, Exception {
		Channel channel = exitResponses.getChannel();
        NettyResponseFuture<?> future = exitResponses.getFuture();
        HttpResponse response = exitResponses.getResponse();
        Request request = exitResponses.getRequest();
        int statusCode = exitResponses.getStatusCode();
        Realm realm = exitResponses.getRealm();
        ProxyServer proxyServer = exitResponses.getProxyServer();
        final NettyRequestSender requestSender = exitResponses.getRequestSender();
        
        if (statusCode == PROXY_AUTHENTICATION_REQUIRED.code() && realm != null && !future.getAndSetAuth(true)) {

            List<String> proxyAuthHeaders = response.headers().getAll(HttpHeaders.Names.PROXY_AUTHENTICATE);

            if (!proxyAuthHeaders.isEmpty()) {
                logger.debug("Sending proxy authentication to {}", request.getUri());
                
                future.setState(NettyResponseFuture.STATE.NEW);
                Realm newRealm = null;
                FluentCaseInsensitiveStringsMap requestHeaders = request.getHeaders();

                boolean negociate = proxyAuthHeaders.contains("Negotiate");
                String ntlmAuthenticate = getNTLM(proxyAuthHeaders);
                
                // to access private method of HttpProtocol
                Class c = HttpProtocol.class;
    			Object obj = c.newInstance();
    			
                if (!proxyAuthHeaders.contains("Kerberos") && ntlmAuthenticate != null) {
        			Method m = c.getDeclaredMethod("ntlmProxyChallenge", new Class[]{String.class, Request.class, ProxyServer.class, FluentCaseInsensitiveStringsMap.class, Realm.class, NettyResponseFuture.class, boolean.class});
        			m.setAccessible(true);
        			System.out.println("Calling ntlmProxyChallenge");
        			newRealm = (Realm)m.invoke(obj, ntlmAuthenticate, request, proxyServer, requestHeaders, realm, future, true);
                    // SPNEGO KERBEROS
                } else if (negociate) {
                	Method m = c.getDeclaredMethod("kerberosChallenge", new Class[]{Channel.class, List.class, Request.class, ProxyServer.class, FluentCaseInsensitiveStringsMap.class, Realm.class,
                            NettyResponseFuture.class, boolean.class});
        			m.setAccessible(true);
        			System.out.println("Calling kerberosChallenge");
        			newRealm = (Realm)m.invoke(obj, channel, proxyAuthHeaders, request, proxyServer, requestHeaders, realm, future, true);
                    if (newRealm == null)
                        return true;
                } else {
                    newRealm = new Realm.RealmBuilder().clone(realm)//
                            .setScheme(realm.getAuthScheme())//
                            .setUri(request.getUri())//
                            .setOmitQuery(true)//
                            .setMethodName(HttpMethod.CONNECT.name())//
                            .setUsePreemptiveAuth(true)//
                            .parseProxyAuthenticateHeader(proxyAuthHeaders.get(0))//
                            .build();
                }

                future.setReuseChannel(true);
                future.setConnectAllowed(true);
                Request nextRequest = new RequestBuilder(future.getRequest()).setHeaders(requestHeaders).setRealm(newRealm).build();
                requestSender.sendNextRequest(nextRequest, future);
                return true;
            }
        }
        
		return false;
	}

}
