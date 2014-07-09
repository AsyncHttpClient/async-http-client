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

package org.asynchttpclient.providers.grizzly.statushandler;

import static org.asynchttpclient.providers.grizzly.statushandler.StatusHandler.InvocationStatus.CONTINUE;

import org.asynchttpclient.Request;
import org.asynchttpclient.providers.grizzly.ConnectionManager;
import org.asynchttpclient.providers.grizzly.EventHandler;
import org.asynchttpclient.providers.grizzly.HttpTxContext;
import org.asynchttpclient.uri.UriComponents;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.util.Header;

public final class RedirectHandler implements StatusHandler {

    public static final RedirectHandler INSTANCE = new RedirectHandler();

    // ------------------------------------------ Methods from StatusHandler

    public boolean handlesStatus(int statusCode) {
        return (EventHandler.isRedirect(statusCode));
    }

    @SuppressWarnings({ "unchecked" })
    public boolean handleStatus(final HttpResponsePacket responsePacket, final HttpTxContext httpTransactionContext,
            final FilterChainContext ctx) {

        final String redirectURL = responsePacket.getHeader(Header.Location);
        if (redirectURL == null) {
            throw new IllegalStateException("redirect received, but no location header was present");
        }

        UriComponents orig;
        if (httpTransactionContext.getLastRedirectURI() == null) {
            orig = httpTransactionContext.getRequest().getURI();
        } else {
            orig = UriComponents.create(httpTransactionContext.getRequest().getURI(),
                    httpTransactionContext.getLastRedirectURI());
        }
        httpTransactionContext.setLastRedirectURI(redirectURL);
        Request requestToSend;
        UriComponents uri = UriComponents.create(orig, redirectURL);
        if (!uri.toUrl().equalsIgnoreCase(orig.toUrl())) {
            requestToSend = EventHandler.newRequest(uri, responsePacket, httpTransactionContext,
                    sendAsGet(responsePacket, httpTransactionContext));
        } else {
            httpTransactionContext.setStatusHandler(null);
            httpTransactionContext.setInvocationStatus(CONTINUE);
            try {
                httpTransactionContext.getHandler().onStatusReceived(httpTransactionContext.getResponseStatus());
            } catch (Exception e) {
                httpTransactionContext.abort(e);
            }
            return true;
        }

        final ConnectionManager m = httpTransactionContext.getProvider().getConnectionManager();
        try {
            final Connection c = m.obtainConnection(requestToSend, httpTransactionContext.getFuture());
            final HttpTxContext newContext = httpTransactionContext.copy();
            httpTransactionContext.setFuture(null);
            newContext.setInvocationStatus(CONTINUE);
            newContext.setRequest(requestToSend);
            newContext.setRequestUri(requestToSend.getURI());
            HttpTxContext.set(ctx, newContext);
            httpTransactionContext.getProvider().execute(c, requestToSend, newContext.getHandler(), newContext.getFuture(), newContext);
            return false;
        } catch (Exception e) {
            httpTransactionContext.abort(e);
        }

        httpTransactionContext.setInvocationStatus(CONTINUE);
        return true;

    }

    // ------------------------------------------------- Private Methods

    private boolean sendAsGet(final HttpResponsePacket response, final HttpTxContext ctx) {
        final int statusCode = response.getStatus();
        return !(statusCode < 302 || statusCode > 303) &&
                !(statusCode == 302 && ctx.getProvider().getClientConfig().isStrict302Handling());
    }

} // END RedirectHandler
