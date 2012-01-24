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
package com.ning.http.client.providers.netty;

import com.ning.http.client.websocket.WebSocket;
import com.ning.http.client.websocket.WebSocketByteListener;
import com.ning.http.client.websocket.WebSocketListener;
import com.ning.http.client.websocket.WebSocketTextListener;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.concurrent.ConcurrentLinkedQueue;

import static org.jboss.netty.buffer.ChannelBuffers.wrappedBuffer;

public class NettyWebSocket implements WebSocket {

    private final Channel channel;
    private final ConcurrentLinkedQueue<WebSocketListener> listeners = new ConcurrentLinkedQueue<WebSocketListener>();

    public NettyWebSocket(Channel channel) {
        this.channel = channel;
    }

    @Override
    public WebSocket sendMessage(byte[] message) {
        channel.write(new BinaryWebSocketFrame(wrappedBuffer(message)));
        return this;
    }

    @Override
    public WebSocket stream(byte[] fragment, boolean last) {
        throw new UnsupportedOperationException("Streaming currently only supported by the Grizzly provider.");
    }

    @Override
    public WebSocket stream(byte[] fragment, int offset, int len, boolean last) {
        throw new UnsupportedOperationException("Streaming currently only supported by the Grizzly provider.");
    }

    @Override
    public WebSocket sendTextMessage(String message) {
        channel.write(new TextWebSocketFrame(message));
        return this;
    }

    @Override
    public WebSocket streamText(String fragment, boolean last) {
        throw new UnsupportedOperationException("Streaming currently only supported by the Grizzly provider.");
    }

    @Override
    public WebSocket sendPing(byte[] payload) {
        channel.write(new PingWebSocketFrame(wrappedBuffer(payload)));
        return this;
    }
    
    @Override
    public WebSocket sendPong(byte[] payload) {
        channel.write(new PongWebSocketFrame(wrappedBuffer(payload)));
        return this;
    }

    @Override
    public WebSocket addMessageListener(WebSocketListener l) {
        listeners.add(l);
        return this;
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() {
        onClose();
        channel.close();
    }

    protected void onMessage(byte[] message) {
        for (WebSocketListener l : listeners) {
            if (WebSocketByteListener.class.isAssignableFrom(l.getClass())) {
                WebSocketByteListener.class.cast(l).onMessage(message);
            }
        }
    }

    protected void onTextMessage(String message) {
        for (WebSocketListener l : listeners) {
            if (WebSocketTextListener.class.isAssignableFrom(l.getClass())) {
                WebSocketTextListener.class.cast(l).onMessage(message);
            }
        }
    }

    protected void onError(Throwable t) {
        for (WebSocketListener l : listeners) {
            l.onError(t);
        }
    }

    protected void onClose() {
        for (WebSocketListener l : listeners) {
            l.onClose(this);
        }
    }

    @Override
    public String toString() {
        return "NettyWebSocket{" +
                "channel=" + channel +
                '}';
    }
}
