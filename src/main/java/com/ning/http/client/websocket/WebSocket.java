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
package com.ning.http.client.websocket;

/**
 * A Websocket client
 */
public interface WebSocket {

    /**
     * Send a byte message.
     * @param message a byte message
     * @return this
     */
    WebSocket sendMessage(byte[] message);

    /**
     * Allows streaming of multiple binary fragments.
     * 
     * @param fragment binary fragment.
     * @param last     flag indicating whether or not this is the last fragment.
     *             
     * @return this.
     */
    WebSocket stream(byte[] fragment, boolean last);

    /**
     * Allows streaming of multiple binary fragments.
     *
     * @param fragment binary fragment.
     * @param offset   starting offset.
     * @param len      length.
     * @param last     flag indicating whether or not this is the last fragment.
     * @return
     */
    WebSocket stream(byte[] fragment, int offset, int len, boolean last);

    /**
     * Send a text message
     * @param message a text message
     * @return this.
     */
    WebSocket sendTextMessage(String message);

    /**
     * Allows streaming of multiple text fragments.
     *
     * @param fragment text fragment.
     * @param last     flag indicating whether or not this is the last fragment.
     * @return this.
     */
    WebSocket streamText(String fragment, boolean last);

    /**
     * Send a <code>ping</ping> with an optional payload
     * (limited to 125 bytes or less).
     *
     * @param payload the ping payload.
     *
     * @return this.
     */
    WebSocket sendPing(byte[] payload);

    /**
     * Send a <code>ping</ping> with an optional payload
     * (limited to 125 bytes or less).
     *
     * @param payload the pong payload.
     * @return this.
     */
    WebSocket sendPong(byte[] payload);

    /**
     * Add a {@link WebSocketListener}
     * @param l a {@link WebSocketListener}
     * @return this
     */
    WebSocket addWebSocketListener(WebSocketListener l);

    /**
     * Add a {@link WebSocketListener}
     * @param l a {@link WebSocketListener}
     * @return this
     */
    WebSocket removeWebSocketListener(WebSocketListener l);

    /**
     * Returns <code>true</code> if the WebSocket is open/connected.
     *
     * @return <code>true</code> if the WebSocket is open/connected.
     */
    boolean isOpen();

    /**
     * Close the WebSocket.
     */
    void close();
}
