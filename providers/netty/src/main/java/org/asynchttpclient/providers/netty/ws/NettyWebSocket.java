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
package org.asynchttpclient.providers.netty.ws;

import static java.nio.charset.StandardCharsets.*;
import static io.netty.buffer.Unpooled.wrappedBuffer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.websocket.WebSocket;
import org.asynchttpclient.websocket.WebSocketByteListener;
import org.asynchttpclient.websocket.WebSocketCloseCodeReasonListener;
import org.asynchttpclient.websocket.WebSocketListener;
import org.asynchttpclient.websocket.WebSocketPingListener;
import org.asynchttpclient.websocket.WebSocketPongListener;
import org.asynchttpclient.websocket.WebSocketTextListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyWebSocket implements WebSocket {

    private NettyWebSocketProduct nettyWebSocketProduct;

	private static final Logger LOGGER = LoggerFactory.getLogger(NettyWebSocket.class);

    protected final Channel channel;
    protected final Collection<WebSocketListener> listeners;
    private volatile boolean interestedInByteMessages;
    private volatile boolean interestedInTextMessages;

    public NettyWebSocket(Channel channel, NettyAsyncHttpProviderConfig nettyConfig) {
        this(channel, nettyConfig, new ConcurrentLinkedQueue<WebSocketListener>());
    }

    public NettyWebSocket(Channel channel, NettyAsyncHttpProviderConfig nettyConfig, Collection<WebSocketListener> listeners) {
        this.nettyWebSocketProduct = new NettyWebSocketProduct(nettyConfig);
		this.channel = channel;
        this.listeners = listeners;
    }

    @Override
    public WebSocket sendMessage(byte[] message) {
        channel.writeAndFlush(new BinaryWebSocketFrame(wrappedBuffer(message)));
        return this;
    }

    @Override
    public WebSocket stream(byte[] fragment, boolean last) {
        channel.writeAndFlush(new BinaryWebSocketFrame(last, 0, wrappedBuffer(fragment)));
        return this;
    }

    @Override
    public WebSocket stream(byte[] fragment, int offset, int len, boolean last) {
        channel.writeAndFlush(new BinaryWebSocketFrame(last, 0, wrappedBuffer(fragment, offset, len)));
        return this;
    }

    @Override
    public WebSocket sendMessage(String message) {
        channel.writeAndFlush(new TextWebSocketFrame(message));
        return this;
    }

    @Override
    public WebSocket stream(String fragment, boolean last) {
        channel.writeAndFlush(new TextWebSocketFrame(last, 0, fragment));
        return this;
    }

    @Override
    public WebSocket sendPing(byte[] payload) {
        channel.writeAndFlush(new PingWebSocketFrame(wrappedBuffer(payload)));
        return this;
    }

    @Override
    public WebSocket sendPong(byte[] payload) {
        channel.writeAndFlush(new PongWebSocketFrame(wrappedBuffer(payload)));
        return this;
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
            channel.writeAndFlush(new CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE);
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

    private boolean hasWebSocketByteListener() {
        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketByteListener)
                return true;
        }
        return false;
    }

    private boolean hasWebSocketTextListener() {
        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketTextListener)
                return true;
        }
        return false;
    }

    @Override
    public WebSocket addWebSocketListener(WebSocketListener l) {
        listeners.add(l);
        interestedInByteMessages = interestedInByteMessages || l instanceof WebSocketByteListener;
        interestedInTextMessages = interestedInTextMessages || l instanceof WebSocketTextListener;
        return this;
    }

    @Override
    public WebSocket removeWebSocketListener(WebSocketListener l) {
        listeners.remove(l);

        if (l instanceof WebSocketByteListener)
            interestedInByteMessages = hasWebSocketByteListener();
        if (l instanceof WebSocketTextListener)
            interestedInTextMessages = hasWebSocketTextListener();

        return this;
    }

    public void notifyByteListeners(byte[] message) {
        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketByteListener)
                WebSocketByteListener.class.cast(listener).onMessage(message);
        }
    }

    public void notifyTextListeners(byte[] bytes) {
        String message = new String(bytes, UTF_8);
        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketTextListener)
                WebSocketTextListener.class.cast(listener).onMessage(message);
        }
    }

    public void onBinaryFragment(HttpResponseBodyPart part) {

        nettyWebSocketProduct.onBinaryFragment(part, listeners,
				interestedInByteMessages, this);
    }

    public void onTextFragment(HttpResponseBodyPart part) {
        nettyWebSocketProduct.onTextFragment(part, listeners,
				interestedInTextMessages, this);
    }

    public void onPing(HttpResponseBodyPart part) {
        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketPingListener)
                // bytes are cached in the part
                WebSocketPingListener.class.cast(listener).onPing(part.getBodyPartBytes());
        }
    }

    public void onPong(HttpResponseBodyPart part) {
        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketPongListener)
                // bytes are cached in the part
                WebSocketPongListener.class.cast(listener).onPong(part.getBodyPartBytes());
        }
    }
}