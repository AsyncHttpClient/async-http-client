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
package org.asynchttpclient.providers.netty.ws;

import org.asynchttpclient.websocket.WebSocket;
import org.asynchttpclient.websocket.WebSocketByteListener;
import org.asynchttpclient.websocket.WebSocketCloseCodeReasonListener;
import org.asynchttpclient.websocket.WebSocketListener;
import org.asynchttpclient.websocket.WebSocketTextListener;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;

import static io.netty.buffer.Unpooled.wrappedBuffer;

public class NettyWebSocket implements WebSocket {
    private final static Logger logger = LoggerFactory.getLogger(NettyWebSocket.class);

    private final Channel channel;
    private final ConcurrentLinkedQueue<WebSocketListener> listeners = new ConcurrentLinkedQueue<WebSocketListener>();

    private StringBuilder textBuffer;
    private ByteArrayOutputStream byteBuffer;
    private int maxBufferSize = 128000000;

    public NettyWebSocket(Channel channel) {
        this.channel = channel;
    }

    @Override
    public WebSocket sendMessage(byte[] message) {
        channel.writeAndFlush(new BinaryWebSocketFrame(wrappedBuffer(message)));
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
        channel.writeAndFlush(new TextWebSocketFrame(message));
        return this;
    }

    @Override
    public WebSocket streamText(String fragment, boolean last) {
        throw new UnsupportedOperationException("Streaming currently only supported by the Grizzly provider.");
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

    public void setMaxBufferSize(int bufferSize) {
        maxBufferSize = bufferSize;

        if (maxBufferSize < 8192)
            maxBufferSize = 8192;
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() {
        onClose();
        listeners.clear();
        try {
            channel.writeAndFlush(new CloseWebSocketFrame());
            channel.closeFuture().awaitUninterruptibly();
        } finally {
            channel.close();
        }
    }

    public void close(int statusCode, String reason) {
        onClose(statusCode, reason);
        listeners.clear();
        try {
            channel.writeAndFlush(new CloseWebSocketFrame());
            channel.closeFuture().awaitUninterruptibly();
        } finally {
            channel.close();
        }
    }

    public void onBinaryFragment(byte[] message, boolean last) {
        for (WebSocketListener l : listeners) {
            if (l instanceof WebSocketByteListener) {
                try {
                    WebSocketByteListener.class.cast(l).onFragment(message, last);

                    if (byteBuffer == null) {
                        byteBuffer = new ByteArrayOutputStream();
                    }

                    byteBuffer.write(message);

                    if (byteBuffer.size() > maxBufferSize) {
                        Exception e = new Exception("Exceeded Netty Web Socket maximum buffer size of " + getMaxBufferSize());
                        l.onError(e);
                        this.close();
                        return;
                    }

                    if (last) {
                        WebSocketByteListener.class.cast(l).onMessage(byteBuffer.toByteArray());
                        byteBuffer = null;
                        textBuffer = null;
                    }
                } catch (Exception ex) {
                    l.onError(ex);
                }
            }
        }
    }

    public void onTextFragment(String message, boolean last) {
        for (WebSocketListener l : listeners) {
            if (l instanceof WebSocketTextListener) {
                try {
                    WebSocketTextListener.class.cast(l).onFragment(message, last);

                    if (textBuffer == null) {
                        textBuffer = new StringBuilder();
                    }

                    textBuffer.append(message);

                    if (textBuffer.length() > maxBufferSize) {
                        Exception e = new Exception("Exceeded Netty Web Socket maximum buffer size of " + getMaxBufferSize());
                        l.onError(e);
                        this.close();
                        return;
                    }

                    if (last) {
                        WebSocketTextListener.class.cast(l).onMessage(textBuffer.toString());
                        byteBuffer = null;
                        textBuffer = null;
                    }
                } catch (Exception ex) {
                    l.onError(ex);
                }
            }
        }
    }

    public void onError(Throwable t) {
        for (WebSocketListener l : listeners) {
            try {
                l.onError(t);
            } catch (Throwable t2) {
                logger.error("", t2);
            }

        }
    }

    public void onClose() {
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
        return "NettyWebSocket{" + "channel=" + channel + '}';
    }
}
