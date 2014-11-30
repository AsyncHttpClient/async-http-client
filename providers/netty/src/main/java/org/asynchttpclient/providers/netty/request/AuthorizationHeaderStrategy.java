package org.asynchttpclient.providers.netty.request;

import static org.asynchttpclient.util.AuthenticatorUtils.computeBasicAuthentication;
import static org.asynchttpclient.util.AuthenticatorUtils.computeDigestAuthentication;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import java.io.IOException;

import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.ntlm.NTLMEngine;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.uri.Uri;

public class AuthorizationHeaderStrategy {

	public interface IAuthorizationHeaderStrategy {

		public String deriveFirstRequestOnlyAuthorizationHeader(Request request, Uri uri, ProxyServer proxyServer) throws IOException;

		public String deriveSystematicAuthorizationHeader(Realm realm);
	}

	public class BASICAuthorizationHeaderStrategy implements IAuthorizationHeaderStrategy {

		@Override
		public String deriveSystematicAuthorizationHeader(Realm realm) {
			return computeBasicAuthentication(realm);
		}

		@Override
		public String deriveFirstRequestOnlyAuthorizationHeader(Request request, Uri uri, ProxyServer proxyServer) throws IOException {
			return null;
		}
	}

	public class DIGESTAuthorizationHeaderStrategy implements IAuthorizationHeaderStrategy {

		@Override
		public String deriveSystematicAuthorizationHeader(Realm realm) {

			String authorizationHeader = null;

			if (isNonEmpty(realm.getNonce()))
                    authorizationHeader = computeDigestAuthentication(realm);

            return authorizationHeader;
		}

		@Override
		public String deriveFirstRequestOnlyAuthorizationHeader(Request request, Uri uri, ProxyServer proxyServer) throws IOException {
			return null;
		}
	}

	public class NTLMAuthorizationHeaderStrategy implements IAuthorizationHeaderStrategy {

		@Override
		public String deriveFirstRequestOnlyAuthorizationHeader(Request request, Uri uri, ProxyServer proxyServer) {

			String msg = NTLMEngine.INSTANCE.generateType1Msg();
			return "NTLM " + msg;
		}

		@Override
		public String deriveSystematicAuthorizationHeader(Realm realm) {
			return null;
		}
	}

	public class KERBEROSAuthorizationHeaderStrategy implements IAuthorizationHeaderStrategy {

		@Override
		public String deriveFirstRequestOnlyAuthorizationHeader(Request request, Uri uri, ProxyServer proxyServer) throws IOException {

			String host;
			if (proxyServer != null)
				host = proxyServer.getHost();
			else if (request.getVirtualHost() != null)
				host = request.getVirtualHost();
			else
				host = uri.getHost();
			try {
				return "Negotiate " + SpnegoEngine.instance().generateToken(host);
			} catch (Throwable e) {
				throw new IOException(e);
			}
		}
		
		@Override
		public String deriveSystematicAuthorizationHeader(Realm realm) {
			return null;
		}
	}


	public class SPNEGOAuthorizationHeaderStrategy implements IAuthorizationHeaderStrategy {

		@Override
		public String deriveFirstRequestOnlyAuthorizationHeader(Request request, Uri uri, ProxyServer proxyServer) throws IOException {
			
			String host;
			if (proxyServer != null)
				host = proxyServer.getHost();
			else if (request.getVirtualHost() != null)
				host = request.getVirtualHost();
			else
				host = uri.getHost();
			try {
				return "Negotiate " + SpnegoEngine.instance().generateToken(host);
			} catch (Throwable e) {
				throw new IOException(e);
			}
		}

		@Override
		public String deriveSystematicAuthorizationHeader(Realm realm) {
			return null;
		}
	}
}