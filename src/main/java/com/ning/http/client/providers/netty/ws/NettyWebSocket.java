/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.netty.ws;

import static org.jboss.netty.buffer.ChannelBuffers.wrappedBuffer;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.websocket.WebSocket;
import com.ning.http.client.websocket.WebSocketCloseCodeReasonListener;
import com.ning.http.client.websocket.WebSocketListener;

import java.util.Collection;

public abstract class NettyWebSocket implements WebSocket {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyWebSocket.class);

    protected final Channel channel;
    protected final Collection<WebSocketListener> listeners;
    protected int maxBufferSize = 128000000;

    public NettyWebSocket(Channel channel, Collection<WebSocketListener> listeners) {
        this.channel = channel;
        this.listeners = listeners;
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
    public WebSocket addWebSocketListener(WebSocketListener l) {
        listeners.add(l);
        return this;
    }

    @Override
    public WebSocket removeWebSocketListener(WebSocketListener l) {
        listeners.remove(l);
        return this;
    }

    public int getMaxBufferSize() {
    	return maxBufferSize;
    }
    
    public void setMaxBufferSize(int maxBufferSize) {
        this.maxBufferSize = Math.max(maxBufferSize, 8192);
    }
    
    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() {
        if (channel.isOpen()) {
            onClose();
            listeners.clear();
            channel.write(new CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE);
        }
    }

    public void close(int statusCode, String reason) {
        onClose(statusCode, reason);
        listeners.clear();
    }

    public void onError(Throwable t) {
        for (WebSocketListener listener : listeners) {
            try {
                listener.onError(t);
            } catch (Throwable t2) {
                LOGGER.error("", t2);
            }
        }
    }

    protected void onClose() {
        onClose(1000, "Normal closure; the connection successfully completed whatever purpose for which it was created.");
    }

    public void onClose(int code, String reason) {
        for (WebSocketListener l : listeners) {
            try {
                if (l instanceof WebSocketCloseCodeReasonListener) {
                    WebSocketCloseCodeReasonListener.class.cast(l).onClose(this, code, reason);
                }
                l.onClose(this);
            } catch (Throwable t) {
                l.onError(t);
            }
        }
    }

    @Override
    public String toString() {
        return "NettyWebSocket{channel=" + channel + '}';
    }
    
    public abstract void onBinaryFragment(HttpResponseBodyPart part);
    
    public abstract void onTextFragment(HttpResponseBodyPart part);
}
