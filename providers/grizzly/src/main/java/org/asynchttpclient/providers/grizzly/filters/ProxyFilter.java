/*
 * Copyright (c) 2013-2014 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package org.asynchttpclient.providers.grizzly.filters;

import static org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider.NTLM_ENGINE;
import static org.asynchttpclient.util.AuthenticatorUtils.computeBasicAuthentication;
import static org.asynchttpclient.util.AuthenticatorUtils.computeDigestAuthentication;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.providers.grizzly.HttpTxContext;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.util.Header;

import java.io.IOException;
import org.glassfish.grizzly.http.HttpPacket;

/**
 * This Filter will be placed in the FilterChain when a request is being
 * proxied.  It's main responsibility is to adjust the incoming request
 * as appropriate for a proxy to properly handle it.
 *
 * @since 2.0.0
 * @author The Grizzly Team
 */
public final class ProxyFilter extends BaseFilter {

    private final ProxyServer proxyServer;
    private final AsyncHttpClientConfig config;
    private final Boolean secure;

    // ------------------------------------------------------------ Constructors

    public ProxyFilter(final ProxyServer proxyServer, final AsyncHttpClientConfig config, boolean secure) {
        this.proxyServer = proxyServer;
        this.config = config;
        this.secure = secure;
    }

    // ----------------------------------------------------- Methods from Filter

    @Override
    public NextAction handleWrite(FilterChainContext ctx) throws IOException {
        final Object msg = ctx.getMessage();
        if (HttpPacket.isHttp(msg)) {
            HttpPacket httpPacket = (HttpPacket) msg;
            final HttpRequestPacket request = (HttpRequestPacket) httpPacket.getHttpHeader();
            if (!request.isCommitted()) {
                HttpTxContext context = HttpTxContext.get(ctx);
                assert (context != null);
                Request req = context.getRequest();
                if (!secure) {
                    request.setRequestURI(req.getUrl());
                }
                addProxyHeaders(getRealm(req), request);
            }
        }
        
        return ctx.getInvokeAction();
    }

    // --------------------------------------------------------- Private Methods

    private void addProxyHeaders(final Realm realm, final HttpRequestPacket request) {
        if (realm != null && realm.getUsePreemptiveAuth()) {
            final String authHeaderValue = generateAuthHeader(realm);
            if (authHeaderValue != null) {
                request.setHeader(Header.ProxyAuthorization, authHeaderValue);
            }
        }
    }

    private Realm getRealm(final Request request) {
        Realm realm = request.getRealm();
        if (realm == null) {
            realm = config.getRealm();
        }
        return realm;
    }

    private String generateAuthHeader(final Realm realm) {
        try {
            switch (realm.getAuthScheme()) {
            case BASIC:
                return computeBasicAuthentication(realm);
            case DIGEST:
                return computeDigestAuthentication(realm);
            case NTLM:
                return NTLM_ENGINE.generateType1Msg("NTLM " + realm.getNtlmDomain(), realm.getNtlmHost());
            default:
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
