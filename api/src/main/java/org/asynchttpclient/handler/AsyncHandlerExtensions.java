/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.handler;

import java.net.InetAddress;

import org.asynchttpclient.AsyncHandler;

/**
 * This interface hosts new low level callback methods on {@link AsyncHandler}.
 * For now, those methods are in a dedicated interface in order not to break the
 * existing API, but could be merged into one of the existing ones in AHC 2.
 * 
 */
public interface AsyncHandlerExtensions {

    /**
     * Notify the callback when trying to open a new connection.
     */
    void onConnectionOpen();

    /**
     * Notify the callback when a new connection was successfully opened.
     */
    void onConnectionOpened();

    /**
     * Notify the callback when trying to fetch a connection from the pool.
     */
    void onConnectionPool();

    /**
     * Notify the callback when a new connection was successfully fetched from
     * the pool.
     */
    void onConnectionPooled();

    /**
     * Notify the callback when a request is being written on the wire. If the
     * original request causes multiple requests to be sent, for example,
     * because of authorization or retry, it will be notified multiple times.
     * 
     * @param request the real request object as passed to the provider
     */
    void onRequestSend(Object request);

    /**
     * Notify the callback every time a request is being retried.
     */
    void onRetry();

    /**
     * Notify the callback after DNS resolution has completed.
     * 
     * @param address the resolved address
     */
    void onDnsResolved(InetAddress address);

    /**
     * Notify the callback when the SSL handshake performed to establish an
     * HTTPS connection has been completed.
     */
    void onSslHandshakeCompleted();
}
