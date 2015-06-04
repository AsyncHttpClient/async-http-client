/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.request;

import static org.asynchttpclient.ntlm.NtlmUtils.getNTLM;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.getAuthority;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.getNonEmptyPath;
import static org.asynchttpclient.util.AuthenticatorUtils.computeBasicAuthentication;
import static org.asynchttpclient.util.AuthenticatorUtils.computeDigestAuthentication;
import static org.asynchttpclient.util.HttpUtils.useProxyConnect;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import java.io.IOException;
import java.util.List;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.ntlm.NtlmEngine;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.uri.Uri;

public abstract class NettyRequestFactoryBase {

    protected final AsyncHttpClientConfig config;

    public NettyRequestFactoryBase(AsyncHttpClientConfig config) {
        this.config = config;
    }

    protected abstract List<String> getProxyAuthorizationHeader(Request request);
    
    protected String firstRequestOnlyProxyAuthorizationHeader(Request request, ProxyServer proxyServer, boolean connect) throws IOException {
        String proxyAuthorization = null;

        if (connect) {
            List<String> auth = getProxyAuthorizationHeader(request);
            String ntlmHeader = getNTLM(auth);
            if (ntlmHeader != null) {
                proxyAuthorization = ntlmHeader;
            }

        } else if (proxyServer != null && proxyServer.getPrincipal() != null && isNonEmpty(proxyServer.getNtlmDomain())) {
            List<String> auth = getProxyAuthorizationHeader(request);
            if (getNTLM(auth) == null) {
                String msg = NtlmEngine.INSTANCE.generateType1Msg();
                proxyAuthorization = "NTLM " + msg;
            }
        }

        return proxyAuthorization;
    }
    
    protected String requestUri(Uri uri, ProxyServer proxyServer, boolean connect) {
        if (connect)
            return getAuthority(uri);

        else if (proxyServer != null && !useProxyConnect(uri))
            return uri.toUrl();

        else {
            String path = getNonEmptyPath(uri);
            if (isNonEmpty(uri.getQuery()))
                return path + "?" + uri.getQuery();
            else
                return path;
        }
    }

    protected String systematicProxyAuthorizationHeader(Request request, ProxyServer proxyServer, Realm realm, boolean connect) {

        String proxyAuthorization = null;

        if (!connect && proxyServer != null && proxyServer.getPrincipal() != null && proxyServer.getScheme() == AuthScheme.BASIC) {
            proxyAuthorization = computeBasicAuthentication(proxyServer);
        } else if (realm != null && realm.getUsePreemptiveAuth() && realm.isTargetProxy()) {

            switch (realm.getScheme()) {
            case BASIC:
                proxyAuthorization = computeBasicAuthentication(realm);
                break;
            case DIGEST:
                if (isNonEmpty(realm.getNonce()))
                    proxyAuthorization = computeDigestAuthentication(realm);
                break;
            case NTLM:
            case KERBEROS:
            case SPNEGO:
                // NTLM, KERBEROS and SPNEGO are only set on the first request,
                // see firstRequestOnlyAuthorizationHeader
            case NONE:
                break;
            default:
                throw new IllegalStateException("Invalid Authentication " + realm);
            }
        }

        return proxyAuthorization;
    }

    protected String firstRequestOnlyAuthorizationHeader(Request request, Uri uri, ProxyServer proxyServer, Realm realm) throws IOException {
        String authorizationHeader = null;

        if (realm != null && realm.getUsePreemptiveAuth()) {
            switch (realm.getScheme()) {
            case NTLM:
                String msg = NtlmEngine.INSTANCE.generateType1Msg();
                authorizationHeader = "NTLM " + msg;
                break;
            case KERBEROS:
            case SPNEGO:
                String host;
                if (proxyServer != null)
                    host = proxyServer.getHost();
                else if (request.getVirtualHost() != null)
                    host = request.getVirtualHost();
                else
                    host = uri.getHost();

                try {
                    authorizationHeader = "Negotiate " + SpnegoEngine.instance().generateToken(host);
                } catch (Throwable e) {
                    throw new IOException(e);
                }
                break;
            default:
                break;
            }
        }

        return authorizationHeader;
    }

    protected String systematicAuthorizationHeader(Request request, Uri uri, Realm realm) {

        String authorizationHeader = null;

        if (realm != null && realm.getUsePreemptiveAuth()) {

            switch (realm.getScheme()) {
            case BASIC:
                authorizationHeader = computeBasicAuthentication(realm);
                break;
            case DIGEST:
                if (isNonEmpty(realm.getNonce()))
                    authorizationHeader = computeDigestAuthentication(realm);
                break;
            case NTLM:
            case KERBEROS:
            case SPNEGO:
                // NTLM, KERBEROS and SPNEGO are only set on the first request,
                // see firstRequestOnlyAuthorizationHeader
            case NONE:
                break;
            default:
                throw new IllegalStateException("Invalid Authentication " + realm);
            }
        }

        return authorizationHeader;
    }
}
