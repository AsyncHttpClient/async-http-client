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
package org.asynchttpclient.ws;

import java.io.Closeable;
import java.net.SocketAddress;

import io.netty.handler.codec.http.HttpHeaders;

/**
 * A WebSocket client
 */
public interface WebSocket extends Closeable {

    /**
     * @return the headers received in the Upgrade response
     */
    HttpHeaders getUpgradeHeaders();

    /**
     * Get remote address client initiated request to.
     * 
     * @return remote address client initiated request to, may be {@code null} if asynchronous provider is unable to provide the remote address
     */
    SocketAddress getRemoteAddress();

    /**
     * Get local address client initiated request from.
     * 
     * @return local address client initiated request from, may be {@code null} if asynchronous provider is unable to provide the local address
     */
    SocketAddress getLocalAddress();

    /**
     * Send a byte message.
     * 
     * @param message a byte message
     * @return this
     */
    WebSocket sendMessage(byte[] message);

    /**
     * Send a byte message.
     *
     * @param message a byte message
     * @param listener is notified when a message was successfully processed by the channel or in case of failure
     * @return this
     */
    WebSocket sendMessage(byte[] message, WebSocketWriteCompleteListener listener);

    /**
     * Allows streaming of multiple binary fragments.
     * 
     * @param fragment binary fragment.
     * @param last flag indicating whether or not this is the last fragment.
     * 
     * @return this
     */
    WebSocket stream(byte[] fragment, boolean last);

    /**
     * Allows streaming of multiple binary fragments.
     *
     * @param fragment binary fragment.
     * @param last flag indicating whether or not this is the last fragment.
     * @param listener is notified when a fragment was successfully processed by the channel or in case of failure
     *
     * @return this
     */
    WebSocket stream(byte[] fragment, boolean last, WebSocketWriteCompleteListener listener);

    /**
     * Allows streaming of multiple binary fragments.
     * 
     * @param fragment binary fragment.
     * @param offset starting offset.
     * @param len length.
     * @param last flag indicating whether or not this is the last fragment.
     * @return this
     */
    WebSocket stream(byte[] fragment, int offset, int len, boolean last);

    /**
     * Allows streaming of multiple binary fragments.
     *
     * @param fragment binary fragment.
     * @param offset starting offset.
     * @param len length.
     * @param last flag indicating whether or not this is the last fragment.
     * @param listener is notified when a fragment was successfully processed by the channel or in case of failure
     * @return this
     */
    WebSocket stream(byte[] fragment, int offset, int len, boolean last, WebSocketWriteCompleteListener listener);

    /**
     * Send a text message
     * 
     * @param message a text message
     * @return this
     */
    WebSocket sendMessage(String message);

    /**
     * Send a text message
     *
     * @param message a text message
     * @param listener is notified when a message was successfully processed by the channel or in case of failure
     * @return this
     */
    WebSocket sendMessage(String message, WebSocketWriteCompleteListener listener);

    /**
     * Allows streaming of multiple text fragments.
     * 
     * @param fragment text fragment.
     * @param last flag indicating whether or not this is the last fragment.
     * @return this
     */
    WebSocket stream(String fragment, boolean last);

    /**
     * Allows streaming of multiple text fragments.
     *
     * @param fragment text fragment.
     * @param last flag indicating whether or not this is the last fragment.
     * @param listener is notified when a fragment was successfully processed by the channel or in case of failure
     * @return this
     */
    WebSocket stream(String fragment, boolean last, WebSocketWriteCompleteListener listener);

    /**
     * Send a <code>ping</code> with an optional payload (limited to 125 bytes or less).
     * 
     * @param payload the ping payload.
     * @return this
     */
    WebSocket sendPing(byte[] payload);

    /**
     * Send a <code>ping</code> with an optional payload (limited to 125 bytes or less).
     *
     * @param payload the ping payload.
     * @param listener is notified when the ping was successfully processed by the channel or in case of failure
     * @return this
     */
    WebSocket sendPing(byte[] payload, WebSocketWriteCompleteListener listener);

    /**
     * Send a <code>ping</code> with an optional payload (limited to 125 bytes or less).
     *
     * @param payload the pong payload.
     * @return this
     */
    WebSocket sendPong(byte[] payload);

    /**
     * Send a <code>ping</code> with an optional payload (limited to 125 bytes or less).
     *
     * @param payload the pong payload.
     * @param listener is notified when the pong was successfully processed by the channel or in case of failure

     * @return this
     */
    WebSocket sendPong(byte[] payload, WebSocketWriteCompleteListener listener);

    /**
     * Add a {@link WebSocketListener}
     * 
     * @param l a {@link WebSocketListener}
     * @return this
     */
    WebSocket addWebSocketListener(WebSocketListener l);

    /**
     * Remove a {@link WebSocketListener}
     * 
     * @param l a {@link WebSocketListener}
     * @return this
     */
    WebSocket removeWebSocketListener(WebSocketListener l);

    /**
     * @return <code>true</code> if the WebSocket is open/connected.
     */
    boolean isOpen();
}
