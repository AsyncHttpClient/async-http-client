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

package org.asynchttpclient.providers.grizzly.websocket;

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import org.asynchttpclient.websocket.WebSocket;
import org.asynchttpclient.websocket.WebSocketListener;
import org.glassfish.grizzly.websockets.SimpleWebSocket;

public final class GrizzlyWebSocketAdapter implements WebSocket {

    private final SimpleWebSocket gWebSocket;
    final boolean bufferFragments;

    // -------------------------------------------------------- Constructors

    public GrizzlyWebSocketAdapter(final SimpleWebSocket gWebSocket, final boolean bufferFragments) {
        this.gWebSocket = gWebSocket;
        this.bufferFragments = bufferFragments;
    }

    // ---------------------------------------------- Methods from AHC WebSocket

    @Override
    public WebSocket sendMessage(byte[] message) {
        gWebSocket.send(message);
        return this;
    }

    @Override
    public WebSocket stream(byte[] fragment, boolean last) {
        if (isNonEmpty(fragment)) {
            gWebSocket.stream(last, fragment, 0, fragment.length);
        }
        return this;
    }

    @Override
    public WebSocket stream(byte[] fragment, int offset, int len, boolean last) {
        if (isNonEmpty(fragment)) {
            gWebSocket.stream(last, fragment, offset, len);
        }
        return this;
    }

    @Override
    public WebSocket sendMessage(String message) {
        gWebSocket.send(message);
        return this;
    }

    @Override
    public WebSocket stream(String fragment, boolean last) {
        gWebSocket.stream(last, fragment);
        return this;
    }

    @Override
    public WebSocket sendPing(byte[] payload) {
        gWebSocket.sendPing(payload);
        return this;
    }

    @Override
    public WebSocket sendPong(byte[] payload) {
        gWebSocket.sendPong(payload);
        return this;
    }

    @Override
    public WebSocket addWebSocketListener(WebSocketListener l) {
        gWebSocket.add(new AHCWebSocketListenerAdapter(l, this));
        return this;
    }

    @Override
    public WebSocket removeWebSocketListener(WebSocketListener l) {
        gWebSocket.remove(new AHCWebSocketListenerAdapter(l, this));
        return this;
    }

    @Override
    public boolean isOpen() {
        return gWebSocket.isConnected();
    }

    @Override
    public void close() {
        gWebSocket.close();
    }

    // ---------------------------------------------------------- Public Methods

    public SimpleWebSocket getGrizzlyWebSocket() {
        return gWebSocket;
    }

} // END GrizzlyWebSocketAdapter
