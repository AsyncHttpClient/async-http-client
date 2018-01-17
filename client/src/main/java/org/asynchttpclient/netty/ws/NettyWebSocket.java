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

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.util.Utf8ByteBufCharsetDecoder;
import org.asynchttpclient.ws.WebSocket;
import org.asynchttpclient.ws.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static org.asynchttpclient.netty.util.ByteBufUtils.byteBuf2Bytes;

public final class NettyWebSocket implements WebSocket {

  private static final Logger LOGGER = LoggerFactory.getLogger(NettyWebSocket.class);

  protected final Channel channel;
  private final HttpHeaders upgradeHeaders;
  private final Collection<WebSocketListener> listeners;
  private FragmentedFrameType expectedFragmentedFrameType;
  // no need for volatile because only mutated in IO thread
  private boolean ready;
  private List<WebSocketFrame> bufferedFrames;

  public NettyWebSocket(Channel channel, HttpHeaders upgradeHeaders) {
    this(channel, upgradeHeaders, new ConcurrentLinkedQueue<>());
  }

  private NettyWebSocket(Channel channel, HttpHeaders upgradeHeaders, Collection<WebSocketListener> listeners) {
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
  public Future<Void> sendTextFrame(String message) {
    return sendTextFrame(message, true, 0);
  }

  @Override
  public Future<Void> sendTextFrame(String payload, boolean finalFragment, int rsv) {
    return channel.writeAndFlush(new TextWebSocketFrame(finalFragment, rsv, payload));
  }

  @Override
  public Future<Void> sendTextFrame(ByteBuf payload, boolean finalFragment, int rsv) {
    return channel.writeAndFlush(new TextWebSocketFrame(finalFragment, rsv, payload));
  }

  @Override
  public Future<Void> sendBinaryFrame(byte[] payload) {
    return sendBinaryFrame(payload, true, 0);
  }

  @Override
  public Future<Void> sendBinaryFrame(byte[] payload, boolean finalFragment, int rsv) {
    return sendBinaryFrame(wrappedBuffer(payload), finalFragment, rsv);
  }

  @Override
  public Future<Void> sendBinaryFrame(ByteBuf payload, boolean finalFragment, int rsv) {
    return channel.writeAndFlush(new BinaryWebSocketFrame(payload));
  }

  @Override
  public Future<Void> sendContinuationFrame(String payload, boolean finalFragment, int rsv) {
    return channel.writeAndFlush(new ContinuationWebSocketFrame(finalFragment, rsv, payload));
  }

  @Override
  public Future<Void> sendContinuationFrame(byte[] payload, boolean finalFragment, int rsv) {
    return sendContinuationFrame(wrappedBuffer(payload), finalFragment, rsv);
  }

  @Override
  public Future<Void> sendContinuationFrame(ByteBuf payload, boolean finalFragment, int rsv) {
    return channel.writeAndFlush(new ContinuationWebSocketFrame(finalFragment, rsv, payload));
  }

  @Override
  public Future<Void> sendPingFrame() {
    return channel.writeAndFlush(new PingWebSocketFrame());
  }

  @Override
  public Future<Void> sendPingFrame(byte[] payload) {
    return sendPingFrame(wrappedBuffer(payload));
  }

  @Override
  public Future<Void> sendPingFrame(ByteBuf payload) {
    return channel.writeAndFlush(new PingWebSocketFrame(payload));
  }

  @Override
  public Future<Void> sendPongFrame() {
    return channel.writeAndFlush(new PongWebSocketFrame());
  }

  @Override
  public Future<Void> sendPongFrame(byte[] payload) {
    return sendPongFrame(wrappedBuffer(payload));
  }

  @Override
  public Future<Void> sendPongFrame(ByteBuf payload) {
    return channel.writeAndFlush(new PongWebSocketFrame(wrappedBuffer(payload)));
  }

  @Override
  public Future<Void> sendCloseFrame() {
    return sendCloseFrame(1000, "normal closure");
  }

  @Override
  public Future<Void> sendCloseFrame(int statusCode, String reasonText) {
    if (channel.isOpen()) {
      return channel.writeAndFlush(new CloseWebSocketFrame(1000, "normal closure"));
    }
    return ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
  }

  @Override
  public boolean isOpen() {
    return channel.isOpen();
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

  // INTERNAL, NOT FOR PUBLIC USAGE!!!

  public boolean isReady() {
    return ready;
  }

  public void bufferFrame(WebSocketFrame frame) {
    if (bufferedFrames == null) {
      bufferedFrames = new ArrayList<>(1);
    }
    frame.retain();
    bufferedFrames.add(frame);
  }

  private void releaseBufferedFrames() {
    if (bufferedFrames != null) {
      for (WebSocketFrame frame : bufferedFrames) {
        frame.release();
      }
      bufferedFrames = null;
    }
  }

  public void processBufferedFrames() {
    ready = true;
    if (bufferedFrames != null) {
      try {
        for (WebSocketFrame frame : bufferedFrames) {
          handleFrame(frame);
        }
      } finally {
        releaseBufferedFrames();
      }
      bufferedFrames = null;
    }
  }

  public void handleFrame(WebSocketFrame frame) {
    if (frame instanceof TextWebSocketFrame) {
      onTextFrame((TextWebSocketFrame) frame);

    } else if (frame instanceof BinaryWebSocketFrame) {
      onBinaryFrame((BinaryWebSocketFrame) frame);

    } else if (frame instanceof CloseWebSocketFrame) {
      Channels.setDiscard(channel);
      CloseWebSocketFrame closeFrame = (CloseWebSocketFrame) frame;
      onClose(closeFrame.statusCode(), closeFrame.reasonText());
      Channels.silentlyCloseChannel(channel);

    } else if (frame instanceof PingWebSocketFrame) {
      onPingFrame((PingWebSocketFrame) frame);

    } else if (frame instanceof PongWebSocketFrame) {
      onPongFrame((PongWebSocketFrame) frame);

    } else if (frame instanceof ContinuationWebSocketFrame) {
      onContinuationFrame((ContinuationWebSocketFrame) frame);
    }
  }

  public void onError(Throwable t) {
    try {
      for (WebSocketListener listener : listeners) {
        try {
          listener.onError(t);
        } catch (Throwable t2) {
          LOGGER.error("WebSocketListener.onError crash", t2);
        }
      }
    } finally {
      releaseBufferedFrames();
    }
  }

  public void onClose(int code, String reason) {
    try {
      for (WebSocketListener l : listeners) {
        try {
          l.onClose(this, code, reason);
        } catch (Throwable t) {
          l.onError(t);
        }
      }
      listeners.clear();
    } finally {
      releaseBufferedFrames();
    }
  }

  @Override
  public String toString() {
    return "NettyWebSocket{channel=" + channel + '}';
  }

  private void onBinaryFrame(BinaryWebSocketFrame frame) {
    if (expectedFragmentedFrameType == null && !frame.isFinalFragment()) {
      expectedFragmentedFrameType = FragmentedFrameType.BINARY;
    }
    onBinaryFrame0(frame);
  }

  private void onBinaryFrame0(WebSocketFrame frame) {
    byte[] bytes = byteBuf2Bytes(frame.content());
    for (WebSocketListener listener : listeners) {
      listener.onBinaryFrame(bytes, frame.isFinalFragment(), frame.rsv());
    }
  }

  private void onTextFrame(TextWebSocketFrame frame) {
    if (expectedFragmentedFrameType == null && !frame.isFinalFragment()) {
      expectedFragmentedFrameType = FragmentedFrameType.TEXT;
    }
    onTextFrame0(frame);
  }

  private void onTextFrame0(WebSocketFrame frame) {
    // faster than frame.text();
    String text = Utf8ByteBufCharsetDecoder.decodeUtf8(frame.content());
    frame.isFinalFragment();
    frame.rsv();
    for (WebSocketListener listener : listeners) {
      listener.onTextFrame(text, frame.isFinalFragment(), frame.rsv());
    }
  }

  private void onContinuationFrame(ContinuationWebSocketFrame frame) {
    if (expectedFragmentedFrameType == null) {
      LOGGER.warn("Received continuation frame without an original text or binary frame, ignoring");
      return;
    }
    try {
      switch (expectedFragmentedFrameType) {
        case BINARY:
          onBinaryFrame0(frame);
          break;
        case TEXT:
          onTextFrame0(frame);
          break;
        default:
          throw new IllegalArgumentException("Unknown FragmentedFrameType " + expectedFragmentedFrameType);
      }
    } finally {
      if (frame.isFinalFragment()) {
        expectedFragmentedFrameType = null;
      }
    }
  }

  private void onPingFrame(PingWebSocketFrame frame) {
    byte[] bytes = byteBuf2Bytes(frame.content());
    for (WebSocketListener listener : listeners) {
      listener.onPingFrame(bytes);
    }
  }

  private void onPongFrame(PongWebSocketFrame frame) {
    byte[] bytes = byteBuf2Bytes(frame.content());
    for (WebSocketListener listener : listeners) {
      listener.onPongFrame(bytes);
    }
  }

  private enum FragmentedFrameType {
    TEXT, BINARY
  }
}
