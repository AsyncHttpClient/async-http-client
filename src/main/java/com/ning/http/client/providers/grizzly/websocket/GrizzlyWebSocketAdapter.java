/*
 * Copyright (c) 2012-2015 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client.providers.grizzly.websocket;

import com.ning.http.client.AsyncHttpProviderConfig;
import com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProviderConfig;
import com.ning.http.client.ws.WebSocket;
import com.ning.http.client.ws.WebSocketListener;
import com.ning.http.util.MiscUtils;
import org.glassfish.grizzly.websockets.ProtocolHandler;
import org.glassfish.grizzly.websockets.SimpleWebSocket;

/**
 * Grizzly AHC {@link WebSocket} adapter.
 */
public final class GrizzlyWebSocketAdapter implements WebSocket {

    /**
     * Create new GrizzlyWebSocketAdapter instance.
     * 
     * @param config
     * @param protocolHandler
     * @return GrizzlyWebSocketAdapter
     */
    public static GrizzlyWebSocketAdapter newInstance(
            final AsyncHttpProviderConfig<?, ?> config,
            final ProtocolHandler protocolHandler) {
        final SimpleWebSocket ws = new SimpleWebSocket(protocolHandler);
        boolean bufferFragments = true;
        if (config instanceof GrizzlyAsyncHttpProviderConfig) {
            bufferFragments = (Boolean) ((GrizzlyAsyncHttpProviderConfig) config)
                    .getProperty(GrizzlyAsyncHttpProviderConfig.Property.BUFFER_WEBSOCKET_FRAGMENTS);
        }
        
        return new GrizzlyWebSocketAdapter(ws, bufferFragments);
    }
    
    
    final SimpleWebSocket gWebSocket;
    final boolean bufferFragments;
    // -------------------------------------------------------- Constructors

    private GrizzlyWebSocketAdapter(final SimpleWebSocket gWebSocket,
            final boolean bufferFragments) {
        this.gWebSocket = gWebSocket;
        this.bufferFragments = bufferFragments;
    }

    public org.glassfish.grizzly.websockets.WebSocket getGrizzlyWebSocket() {
        return gWebSocket;
    }
    
    // ------------------------------------------ Methods from AHC WebSocket
    @Override
    public WebSocket sendMessage(byte[] message) {
        gWebSocket.send(message);
        return this;
    }

    @Override
    public WebSocket stream(byte[] fragment, boolean last) {
        if (MiscUtils.isNonEmpty(fragment)) {
            gWebSocket.stream(last, fragment, 0, fragment.length);
        }
        return this;
    }

    @Override
    public WebSocket stream(byte[] fragment, int offset, int len, boolean last) {
        if (MiscUtils.isNonEmpty(fragment)) {
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
    
} // END GrizzlyWebSocketAdapter
