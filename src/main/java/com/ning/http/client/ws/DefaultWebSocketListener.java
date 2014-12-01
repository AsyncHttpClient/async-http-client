/*
 * Copyright (c) 2012-2013 Sonatype, Inc. All rights reserved.
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

package com.ning.http.client.ws;

/**
 * Default WebSocketListener implementation.  Most methods are no-ops.  This 
 * allows for quick override customization without clutter of methods that the
 * developer isn't interested in dealing with.
 * 
 * @since 1.7.0
 */
public class DefaultWebSocketListener implements  WebSocketByteListener, WebSocketTextListener, WebSocketPingListener, WebSocketPongListener {

    protected WebSocket webSocket;
    
    // -------------------------------------- Methods from WebSocketByteListener

    @Override
    public void onMessage(byte[] message) {
    }

    // -------------------------------------- Methods from WebSocketPingListener
    
    @Override
    public void onPing(byte[] message) {
    }

    
    // -------------------------------------- Methods from WebSocketPongListener
    
    @Override
    public void onPong(byte[] message) {
    }
    
    
    // -------------------------------------- Methods from WebSocketTextListener


    @Override
    public void onMessage(String message) {
    }
    
    // ------------------------------------------ Methods from WebSocketListener

    @Override
    public void onOpen(WebSocket websocket) {
        this.webSocket = websocket;
    }

    @Override
    public void onClose(WebSocket websocket) {
        this.webSocket = null;
    }

    @Override
    public void onError(Throwable t) {
    }
}
