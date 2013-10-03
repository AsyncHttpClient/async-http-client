/*
 * Copyright (c) 2013 Sonatype, Inc. All rights reserved.
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

import org.asynchttpclient.providers.grizzly.filters.events.SSLSwitchingEvent;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.ssl.SSLFilter;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;


/**
 * SSL Filter that may be present within the FilterChain and may be
 * enabled/disabled by sending the appropriate {@link SSLSwitchingEvent}.
 *
 * @since 2.0
 * @author The Grizzly Team
 */
public final class SwitchingSSLFilter extends SSLFilter {

    private static final Attribute<Boolean> CONNECTION_IS_SECURE =
        Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(SwitchingSSLFilter.class.getName());
    private static final Attribute<Throwable> HANDSHAKE_ERROR =
        Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(SwitchingSSLFilter.class.getName() + "-HANDSHAKE-ERROR");


    // ------------------------------------------------------------ Constructors


    public SwitchingSSLFilter(final SSLEngineConfigurator clientConfig) {

        super(null, clientConfig);

    }


    // -------------------------------------------------- Methods from SSLFilter


    @Override
    protected void notifyHandshakeFailed(Connection connection, Throwable t) {
        setError(connection, t);
    }

    @Override
    public NextAction handleConnect(final FilterChainContext ctx) throws IOException {
        // Suspend further handleConnect processing.  We do this to ensure that
        // the SSL handshake has been completed before returning the connection
        // for use in processing user requests.  Additionally, this allows us
        // to determine if a connection is SPDY or HTTP as early as possible.
        ctx.suspend();
        final Connection c = ctx.getConnection();
        handshake(ctx.getConnection(),
                  new EmptyCompletionHandler<SSLEngine>() {
                      @Override
                      public void completed(SSLEngine result) {
                          // Handshake was successful.  Resume the handleConnect
                          // processing.  We pass in Invoke Action so the filter
                          // chain will call handleConnect on the next filter.
                          ctx.resume(ctx.getInvokeAction());
                      }

                      @Override
                      public void cancelled() {
                          // Handshake was cancelled.  Stop the handleConnect
                          // processing.  The exception will be checked and
                          // passed to the user later.
                          setError(c, new SSLHandshakeException(
                                  "Handshake canceled."));
                          ctx.resume(ctx.getStopAction());
                      }

                      @Override
                      public void failed(Throwable throwable) {
                          // Handshake failed.  Stop the handleConnect
                          // processing.  The exception will be checked and
                          // passed to the user later.
                          setError(c, throwable);
                          ctx.resume(ctx.getStopAction());
                      }
                  });

        // This typically isn't advised, however, we need to be able to
        // read the response from the proxy and OP_READ isn't typically
        // enabled on the connection until all of the handleConnect()
        // processing is complete.
        enableRead(c);

        // Tell the FilterChain that we're suspending further handleConnect
        // processing.
        return ctx.getSuspendAction();
    }

    @Override
    public NextAction handleEvent(final FilterChainContext ctx,
                                  final FilterChainEvent event) throws IOException {

        if (event.type() == SSLSwitchingEvent.class) {
            final SSLSwitchingEvent se = (SSLSwitchingEvent) event;
            setSecureStatus(se.getConnection(), se.isSecure());
            return ctx.getStopAction();
        }
        return ctx.getInvokeAction();

    }

    @Override
    public NextAction handleRead(final FilterChainContext ctx) throws IOException {

        if (isSecure(ctx.getConnection())) {
            return super.handleRead(ctx);
        }
        return ctx.getInvokeAction();

    }

    @Override
    public NextAction handleWrite(final FilterChainContext ctx) throws IOException {

        if (isSecure(ctx.getConnection())) {
            return super.handleWrite(ctx);
        }
        return ctx.getInvokeAction();

    }

    @Override
    public void onFilterChainChanged(final FilterChain filterChain) {
        // no-op
    }


    public static Throwable getHandshakeError(final Connection c) {
        return HANDSHAKE_ERROR.remove(c);
    }


    // --------------------------------------------------------- Private Methods


    private static boolean isSecure(final Connection c) {
        Boolean secStatus = CONNECTION_IS_SECURE.get(c);
        return (secStatus == null ? true : secStatus);
    }

    private static void setSecureStatus(final Connection c, final boolean secure) {
        CONNECTION_IS_SECURE.set(c, secure);
    }

    private static void setError(final Connection c, Throwable t) {
        HANDSHAKE_ERROR.set(c, t);
    }

    private static void enableRead(final Connection c) throws IOException {
        c.enableIOEvent(IOEvent.READ);
    }

}
