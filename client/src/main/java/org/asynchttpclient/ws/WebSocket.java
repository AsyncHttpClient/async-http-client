/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.ws;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.concurrent.Future;

import java.net.SocketAddress;

/**
 * A WebSocket client interface for sending and receiving WebSocket frames.
 * <p>
 * This interface provides methods for sending various types of WebSocket frames
 * (text, binary, ping, pong, close) and managing WebSocket listeners. It supports
 * both simple and advanced use cases, including message fragmentation and WebSocket
 * extensions through RSV bits.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Simple text message
 * websocket.sendTextFrame("Hello, WebSocket!");
 *
 * // Binary message
 * byte[] data = {1, 2, 3, 4};
 * websocket.sendBinaryFrame(data);
 *
 * // Fragmented message (3 parts)
 * websocket.sendTextFrame("First part ", false, 0);
 * websocket.sendContinuationFrame("middle part ", false, 0);
 * websocket.sendContinuationFrame("last part", true, 0);
 *
 * // Ping/Pong for keepalive
 * websocket.sendPingFrame();
 *
 * // Close connection
 * websocket.sendCloseFrame(1000, "Normal closure");
 * }</pre>
 */
public interface WebSocket {

  /**
   * Returns the HTTP headers received in the WebSocket upgrade response.
   * <p>
   * These headers may include server information, protocol negotiation results,
   * and custom headers sent by the server during the handshake.
   * </p>
   *
   * @return the headers received in the upgrade response
   */
  HttpHeaders getUpgradeHeaders();

  /**
   * Get remote address client initiated request to.
   *
   * @return remote address client initiated request to, may be {@code null} if asynchronous provider is unable to provide the remote address
   */
  SocketAddress getRemoteAddress();

  /**
   * Get local address client initiated request from.
   *
   * @return local address client initiated request from, may be {@code null} if asynchronous provider is unable to provide the local address
   */
  SocketAddress getLocalAddress();

  /**
   * Send a full text frame
   *
   * @param payload a text payload
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendTextFrame(String payload);

  /**
   * Allows sending a text frame with fragmentation or extension bits. When using fragmentation, the next fragments must be sent with sendContinuationFrame.
   *
   * @param payload       a text fragment.
   * @param finalFragment flag indicating whether or not this is the final fragment
   * @param rsv           extension bits, 0 otherwise
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendTextFrame(String payload, boolean finalFragment, int rsv);

  /**
   * Allows sending a text frame with fragmentation or extension bits. When using fragmentation, the next fragments must be sent with sendContinuationFrame.
   *
   * @param payload       a ByteBuf fragment.
   * @param finalFragment flag indicating whether or not this is the final fragment
   * @param rsv           extension bits, 0 otherwise
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendTextFrame(ByteBuf payload, boolean finalFragment, int rsv);

  /**
   * Send a full binary frame.
   *
   * @param payload a binary payload
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendBinaryFrame(byte[] payload);

  /**
   * Allows sending a binary frame with fragmentation or extension bits. When using fragmentation, the next fragments must be sent with sendContinuationFrame.
   *
   * @param payload       a binary payload
   * @param finalFragment flag indicating whether or not this is the last fragment
   * @param rsv           extension bits, 0 otherwise
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendBinaryFrame(byte[] payload, boolean finalFragment, int rsv);

  /**
   * Allows sending a binary frame with fragmentation or extension bits. When using fragmentation, the next fragments must be sent with sendContinuationFrame.
   *
   * @param payload       a ByteBuf payload
   * @param finalFragment flag indicating whether or not this is the last fragment
   * @param rsv           extension bits, 0 otherwise
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendBinaryFrame(ByteBuf payload, boolean finalFragment, int rsv);

  /**
   * Send a text continuation frame. The last fragment must have finalFragment set to true.
   *
   * @param payload       the text fragment
   * @param finalFragment flag indicating whether or not this is the last fragment
   * @param rsv           extension bits, 0 otherwise
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendContinuationFrame(String payload, boolean finalFragment, int rsv);

  /**
   * Send a binary continuation frame. The last fragment must have finalFragment set to true.
   *
   * @param payload       the binary fragment
   * @param finalFragment flag indicating whether or not this is the last fragment
   * @param rsv           extension bits, 0 otherwise
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendContinuationFrame(byte[] payload, boolean finalFragment, int rsv);

  /**
   * Send a continuation frame (those are actually untyped as counterpart must have memorized first fragmented frame type). The last fragment must have finalFragment set to true.
   *
   * @param payload       a ByteBuf fragment
   * @param finalFragment flag indicating whether or not this is the last fragment
   * @param rsv           extension bits, 0 otherwise
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendContinuationFrame(ByteBuf payload, boolean finalFragment, int rsv);

  /**
   * Send a empty ping frame
   *
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendPingFrame();

  /**
   * Send a ping frame with a byte array payload (limited to 125 bytes or less).
   *
   * @param payload the payload.
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendPingFrame(byte[] payload);

  /**
   * Send a ping frame with a ByteBuf payload (limited to 125 bytes or less).
   *
   * @param payload the payload.
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendPingFrame(ByteBuf payload);

  /**
   * Send a empty pong frame
   *
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendPongFrame();

  /**
   * Send a pong frame with a byte array payload (limited to 125 bytes or less).
   *
   * @param payload the payload.
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendPongFrame(byte[] payload);

  /**
   * Send a pong frame with a ByteBuf payload (limited to 125 bytes or less).
   *
   * @param payload the payload.
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendPongFrame(ByteBuf payload);

  /**
   * Send a empty close frame.
   *
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendCloseFrame();

  /**
   * Send a empty close frame.
   *
   * @param statusCode a status code
   * @param reasonText a reason
   * @return a future that will be completed once the frame will be actually written on the wire
   */
  Future<Void> sendCloseFrame(int statusCode, String reasonText);

  /**
   * @return <code>true</code> if the WebSocket is open/connected.
   */
  boolean isOpen();

  /**
   * Add a {@link WebSocketListener}
   *
   * @param l a {@link WebSocketListener}
   * @return this
   */
  WebSocket addWebSocketListener(WebSocketListener l);

  /**
   * Remove a {@link WebSocketListener}
   *
   * @param l a {@link WebSocketListener}
   * @return this
   */
  WebSocket removeWebSocketListener(WebSocketListener l);
}
