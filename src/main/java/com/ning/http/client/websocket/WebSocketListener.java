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
 * A generic {@link WebSocketListener} for WebSocket events. Use the appropriate listener for receiving message bytes.
 */
public interface WebSocketListener {

    /**
     * Invoked when the {@link WebSocket} is open.
     *
     * @param websocket
     */
    void onOpen(WebSocket websocket);

    /**
     * Invoked when the {@link WebSocket} is close.
     *
     * @param websocket
     */
    void onClose(WebSocket websocket);

    /**
     * Invoked when the {@link WebSocket} is open.
     *
     * @param t a {@link Throwable}
     */
    void onError(Throwable t);

}
