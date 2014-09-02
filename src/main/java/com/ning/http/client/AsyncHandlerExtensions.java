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
package com.ning.http.client;

/**
 * This interface hosts new low level callback methods on {@link AsyncHandler}.
 * For now, those methods are in a dedicated interface in order not to break the existing API,
 * but could be merged into one of the existing ones in AHC 2.
 * 
 * More additional hooks might come, such as:
 * <ul>
 *   <li>onConnectionClosed()</li>
 *   <li>onBytesSent(long numberOfBytes)</li>
 *   <li>onBytesReceived(long numberOfBytes)</li>
 * </ul>
 */
public interface AsyncHandlerExtensions {

    /**
     * Notify the callback when trying to open a new connection.
     */
    void onOpenConnection();

    /**
     * Notify the callback when a new connection was successfully opened.
     */
    void onConnectionOpen();

    /**
     * Notify the callback when trying to fetch a connection from the pool.
     */
    void onPoolConnection();

    /**
     * Notify the callback when a new connection was successfully fetched from the pool.
     */
    void onConnectionPooled();

    /**
     * Notify the callback when a request is about to be written on the wire.
     * If the original request causes multiple requests to be sent, for example, because of authorization or retry,
     * it will be notified multiple times.
     */
    void onSendRequest();

    /**
     * Notify the callback every time a request is being retried.
     */
    void onRetry();
}
