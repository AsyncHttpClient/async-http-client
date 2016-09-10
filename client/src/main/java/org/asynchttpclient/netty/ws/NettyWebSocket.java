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
package org.asynchttpclient.netty.ws;

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.netty.util.ByteBufUtils.byteBuf2Bytes;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.net.SocketAddress;
import java.nio.charset.CharacterCodingException;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.asynchttpclient.netty.util.ByteBufUtils;
import org.asynchttpclient.ws.WebSocket;
import org.asynchttpclient.ws.WebSocketByteListener;
import org.asynchttpclient.ws.WebSocketCloseCodeReasonListener;
import org.asynchttpclient.ws.WebSocketListener;
import org.asynchttpclient.ws.WebSocketPingListener;
import org.asynchttpclient.ws.WebSocketPongListener;
import org.asynchttpclient.ws.WebSocketTextListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyWebSocket implements WebSocket {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyWebSocket.class);

    protected final Channel channel;
    protected final HttpHeaders upgradeHeaders;
    protected final Collection<WebSocketListener> listeners;
    private volatile boolean interestedInByteMessages;
    private volatile boolean interestedInTextMessages;

    public NettyWebSocket(Channel channel, HttpHeaders upgradeHeaders) {
        this(channel, upgradeHeaders, new ConcurrentLinkedQueue<>());
    }

    public NettyWebSocket(Channel channel, HttpHeaders upgradeHeaders, Collection<WebSocketListener> listeners) {
        this.channel = channel;
        this.upgradeHeaders = upgradeHeaders;
        this.listeners = listeners;
    }

    @Override
    public HttpHeaders getUpgradeHeaders() {
        return upgradeHeaders;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return channel.remoteAddress();
    }

    @Override
    public SocketAddress getLocalAddress() {
        return channel.localAddress();
    }

    @Override
    public WebSocket sendMessage(byte[] message) {
        channel.writeAndFlush(new BinaryWebSocketFrame(wrappedBuffer(message)), channel.voidPromise());
        return this;
    }

    @Override
    public WebSocket stream(byte[] fragment, boolean last) {
        channel.writeAndFlush(new BinaryWebSocketFrame(last, 0, wrappedBuffer(fragment)), channel.voidPromise());
        return this;
    }

    @Override
    public WebSocket stream(byte[] fragment, int offset, int len, boolean last) {
        channel.writeAndFlush(new BinaryWebSocketFrame(last, 0, wrappedBuffer(fragment, offset, len)), channel.voidPromise());
        return this;
    }

    @Override
    public WebSocket sendMessage(String message) {
        channel.writeAndFlush(new TextWebSocketFrame(message), channel.voidPromise());
        return this;
    }

    @Override
    public WebSocket stream(String fragment, boolean last) {
        channel.writeAndFlush(new TextWebSocketFrame(last, 0, fragment), channel.voidPromise());
        return this;
    }

    @Override
    public WebSocket sendPing(byte[] payload) {
        channel.writeAndFlush(new PingWebSocketFrame(wrappedBuffer(payload)), channel.voidPromise());
        return this;
    }

    @Override
    public WebSocket sendPong(byte[] payload) {
        channel.writeAndFlush(new PongWebSocketFrame(wrappedBuffer(payload)), channel.voidPromise());
        return this;
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() {
        if (channel.isOpen()) {
            channel.writeAndFlush(new CloseWebSocketFrame(1000, "normal closure"));
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
                LOGGER.error("WebSocketListener.onError crash", t2);
            }
        }
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

    private void notifyByteListeners(byte[] message) {
        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketByteListener)
                WebSocketByteListener.class.cast(listener).onMessage(message);
        }
    }

    private void notifyTextListeners(String message) {
        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketTextListener)
                WebSocketTextListener.class.cast(listener).onMessage(message);
        }
    }

    public void onBinaryFrame(BinaryWebSocketFrame frame) {
        if (interestedInByteMessages) {
            notifyByteListeners(byteBuf2Bytes(frame.content()));
        }
    }

    public void onTextFrame(TextWebSocketFrame frame) {
        if (interestedInTextMessages) {
            try {
                notifyTextListeners(ByteBufUtils.byteBuf2String(UTF_8, frame.content()));
            } catch (CharacterCodingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public void onPing(PingWebSocketFrame frame) {
        byte[] bytes = byteBuf2Bytes(frame.content());
        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketPingListener)
                WebSocketPingListener.class.cast(listener).onPing(bytes);
        }
    }

    public void onPong(PongWebSocketFrame frame) {
        byte[] bytes = byteBuf2Bytes(frame.content());
        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketPongListener)
                WebSocketPongListener.class.cast(listener).onPong(bytes);
        }
    }
}
