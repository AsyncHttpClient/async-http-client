/*
 * Copyright (c) 2010-2011 Sonatype, Inc. All rights reserved.
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
     * Sen a byte message.
     * @param message a byte message
     * @return this
     */
    WebSocket sendMessage(byte[] message);

    /**
     * Send a text message
     * @param message a text message
     * @return this.
     */
    WebSocket sendTextMessage(String message);

    /**
     * Add a {@link WebSocketListener}
     * @param l a {@link WebSocketListener}
     * @return this
     */
    WebSocket addMessageListener(WebSocketListener l);

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
