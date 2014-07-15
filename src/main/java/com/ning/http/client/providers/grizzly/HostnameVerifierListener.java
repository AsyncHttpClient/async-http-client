/*
 * Copyright (c) 2014 Sonatype, Inc. All rights reserved.
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

package com.ning.http.client.providers.grizzly;

import com.ning.http.util.Base64;
import java.io.IOException;
import java.net.ConnectException;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.ssl.SSLBaseFilter;
import org.glassfish.grizzly.ssl.SSLUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SSL handshake listener, that checks the SSL session hostname after
 * handshake is completed.
 * 
 * @author Grizzly Team
 */
class HostnameVerifierListener implements SSLBaseFilter.HandshakeListener {
    private final static Logger LOGGER = LoggerFactory.getLogger(HostnameVerifierListener.class);
    
    private static final Attribute<HostnameVerifierTask> VERIFIER_TASK_ATTR =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
                    HostnameVerifierTask.class.getName());

    public HostnameVerifierListener() {
    }
    
    @Override
    public void onStart(final Connection connection) {
        // do nothing
        LOGGER.debug("SSL Handshake onStart: ");
    }

    @Override
    public void onComplete(final Connection connection) {
        final HostnameVerifierTask task = VERIFIER_TASK_ATTR.remove(connection);
        if (task != null) {
            task.verify();
        }
    }
    
    static CompletionHandler<Connection> wrapWithHostnameVerifierHandler(
            final CompletionHandler<Connection> completionHandler,
            final HostnameVerifier verifier, final String host) {

        return new CompletionHandler<Connection>() {

            public void cancelled() {
                if (completionHandler != null) {
                    completionHandler.cancelled();
                }
            }

            public void failed(final Throwable throwable) {
                if (completionHandler != null) {
                    completionHandler.failed(throwable);
                }
            }

            public void completed(final Connection connection) {
                assignHostnameVerifyTask(connection, verifier, host,
                        completionHandler);
                
                if (completionHandler != null) {
                    completionHandler.completed(connection);
                }
            }

            public void updated(final Connection connection) {
                if (completionHandler != null) {
                    completionHandler.updated(connection);
                }
            }
        };
    }
    
    private static void assignHostnameVerifyTask(final Connection connection,
            final HostnameVerifier verifier, final String host,
            final CompletionHandler<Connection> delegate) {
        final HostnameVerifierTask task = new HostnameVerifierTask(
                verifier, connection, host, delegate);
        VERIFIER_TASK_ATTR.set(connection, task);
    }
    
    private static class HostnameVerifierTask {
        private final HostnameVerifier verifier;
        private final Connection connection;
        private final String host;
        private final CompletionHandler<Connection> delegate;

        public HostnameVerifierTask(final HostnameVerifier verifier,
                final Connection connection,
                final String host,
                final CompletionHandler<Connection> delegate) {
            this.verifier = verifier;
            this.connection = connection;
            this.host = host;
            this.delegate = delegate;
        }

        public void verify() {
            final SSLSession session = SSLUtils.getSSLEngine(connection).getSession();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("SSL Handshake onComplete: session = {}, id = {}, isValid = {}, host = {}",
                        session.toString(), Base64.encode(session.getId()), session.isValid(), host);
            }

            if (!verifier.verify(host, session)) {
                connection.close(); // XXX what's the correct way to kill a connection?
                IOException e = new ConnectException("Host name verification failed for host " + host);
                delegate.failed(e);
            }
        }
    }
}
