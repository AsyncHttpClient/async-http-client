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

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static org.asynchttpclient.util.StandardCharsets.UTF_8;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.response.NettyResponseBodyPart;
import org.asynchttpclient.websocket.WebSocket;
import org.asynchttpclient.websocket.WebSocketByteFragmentListener;
import org.asynchttpclient.websocket.WebSocketByteListener;
import org.asynchttpclient.websocket.WebSocketCloseCodeReasonListener;
import org.asynchttpclient.websocket.WebSocketListener;
import org.asynchttpclient.websocket.WebSocketPingListener;
import org.asynchttpclient.websocket.WebSocketPongListener;
import org.asynchttpclient.websocket.WebSocketTextFragmentListener;
import org.asynchttpclient.websocket.WebSocketTextListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyWebSocket implements WebSocket {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyWebSocket.class);

    protected final Channel channel;
    protected final Collection<WebSocketListener> listeners;
    protected final int maxBufferSize;
    private int bufferSize;
    private List<byte[]> _fragments;
    private volatile boolean interestedInByteMessages;
    private volatile boolean interestedInTextMessages;

    public NettyWebSocket(Channel channel, NettyAsyncHttpProviderConfig nettyConfig) {
        this(channel, nettyConfig, new ConcurrentLinkedQueue<WebSocketListener>());
    }

    public NettyWebSocket(Channel channel, NettyAsyncHttpProviderConfig nettyConfig, Collection<WebSocketListener> listeners) {
        this.channel = channel;
        this.listeners = listeners;
        maxBufferSize = nettyConfig.getWebSocketMaxBufferSize();
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
    public WebSocket sendTextMessage(String message) {
        channel.writeAndFlush(new TextWebSocketFrame(message));
        return this;
    }

    @Override
    public WebSocket streamText(String fragment, boolean last) {
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
        if (l instanceof WebSocketByteListener)
            interestedInByteMessages = true;
        else if (l instanceof WebSocketTextListener)
            interestedInTextMessages = true;
        return this;
    }

    @Override
    public WebSocket removeWebSocketListener(WebSocketListener l) {
        listeners.remove(l);

        if (l instanceof WebSocketByteListener)
            interestedInByteMessages = hasWebSocketByteListener();
        else if (l instanceof WebSocketTextListener)
            interestedInTextMessages = hasWebSocketTextListener();

        return this;
    }

    private List<byte[]> fragments() {
        if (_fragments == null)
            _fragments = new ArrayList<byte[]>(2);
        return _fragments;
    }

    private void bufferFragment(byte[] buffer) {
        bufferSize += buffer.length;
        if (bufferSize > maxBufferSize) {
            onError(new Exception("Exceeded Netty Web Socket maximum buffer size of " + maxBufferSize));
            reset();
            close();
        } else {
            fragments().add(buffer);
        }
    }

    private void reset() {
        fragments().clear();
        bufferSize = 0;
    }

    private void notifyByteListeners(byte[] message) {
        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketByteListener)
                WebSocketByteListener.class.cast(listener).onMessage(message);
        }
    }

    private void notifyTextListeners(byte[] bytes) {
        String message = new String(bytes, UTF_8);
        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketTextListener)
                WebSocketTextListener.class.cast(listener).onMessage(message);
        }
    }

    public void onBinaryFragment(HttpResponseBodyPart part) {

        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketByteFragmentListener)
                WebSocketByteFragmentListener.class.cast(listener).onFragment(part);
        }

        if (interestedInByteMessages) {
            byte[] fragment = NettyResponseBodyPart.class.cast(part).getBodyPartBytes();

            if (part.isLast()) {
                if (bufferSize == 0) {
                    notifyByteListeners(fragment);

                } else {
                    bufferFragment(fragment);
                    notifyByteListeners(fragmentsBytes());
                }

                reset();

            } else
                bufferFragment(fragment);
        }
    }

    private byte[] fragmentsBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream(bufferSize);
        for (byte[] bytes : _fragments)
            try {
                os.write(bytes);
            } catch (IOException e) {
                // yeah, right
            }
        return os.toByteArray();
    }

    public void onTextFragment(HttpResponseBodyPart part) {
        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketTextFragmentListener)
                WebSocketTextFragmentListener.class.cast(listener).onFragment(part);
        }

        if (interestedInTextMessages) {
            byte[] fragment = NettyResponseBodyPart.class.cast(part).getBodyPartBytes();

            if (part.isLast()) {
                if (bufferSize == 0) {
                    notifyTextListeners(fragment);

                } else {
                    bufferFragment(fragment);
                    notifyTextListeners(fragmentsBytes());
                }

                reset();

            } else
                bufferFragment(fragment);
        }
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
