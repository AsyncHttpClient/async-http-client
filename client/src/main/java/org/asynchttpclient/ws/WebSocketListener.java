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
package org.asynchttpclient.ws;

/**
 * Listener interface for WebSocket events and frame notifications.
 * <p>
 * This interface provides callbacks for WebSocket lifecycle events (open, close, error)
 * and frame-level notifications (text, binary, ping, pong). Implementations can selectively
 * override the frame methods they're interested in, as they provide default no-op implementations.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * WebSocketListener listener = new WebSocketListener() {
 *     @Override
 *     public void onOpen(WebSocket websocket) {
 *         System.out.println("WebSocket opened");
 *         websocket.sendTextFrame("Hello");
 *     }
 *
 *     @Override
 *     public void onTextFrame(String payload, boolean finalFragment, int rsv) {
 *         System.out.println("Received: " + payload);
 *     }
 *
 *     @Override
 *     public void onClose(WebSocket websocket, int code, String reason) {
 *         System.out.println("WebSocket closed: " + code + " " + reason);
 *     }
 *
 *     @Override
 *     public void onError(Throwable t) {
 *         System.err.println("WebSocket error: " + t.getMessage());
 *     }
 * };
 * }</pre>
 */
public interface WebSocketListener {

  /**
   * Invoked when the {@link WebSocket} connection is successfully established.
   * <p>
   * This callback is called after the WebSocket handshake completes successfully.
   * The connection is now ready to send and receive frames.
   * </p>
   *
   * @param websocket the WebSocket instance that was opened
   */
  void onOpen(WebSocket websocket);

  /**
   * Invoked when the {@link WebSocket} connection is closed.
   * <p>
   * This callback is triggered when a close frame is received or the connection
   * is terminated. The status code and reason provide information about why
   * the connection was closed.
   * </p>
   *
   * @param websocket the WebSocket instance that was closed
   * @param code the status code indicating the reason for closure (e.g., 1000 for normal closure)
   * @param reason a textual description of the closure reason (may be empty)
   * @see <a href="http://tools.ietf.org/html/rfc6455#section-5.5.1">RFC 6455 Section 5.5.1</a>
   */
  void onClose(WebSocket websocket, int code, String reason);

  /**
   * Invoked when an error occurs on the {@link WebSocket} connection.
   * <p>
   * This callback is triggered when an exception occurs during WebSocket
   * communication, such as network errors, protocol violations, or application-level
   * exceptions.
   * </p>
   *
   * @param t the Throwable that caused the error
   */
  void onError(Throwable t);

  /**
   * Invoked when a binary frame is received.
   *
   * @param payload       a byte array
   * @param finalFragment true if this frame is the final fragment
   * @param rsv           extension bits
   */
  default void onBinaryFrame(byte[] payload, boolean finalFragment, int rsv) {
  }

  /**
   * Invoked when a text frame is received.
   *
   * @param payload       a UTF-8 {@link String} message
   * @param finalFragment true if this frame is the final fragment
   * @param rsv           extension bits
   */
  default void onTextFrame(String payload, boolean finalFragment, int rsv) {
  }

  /**
   * Invoked when a ping frame is received
   *
   * @param payload a byte array
   */
  default void onPingFrame(byte[] payload) {
  }

  /**
   * Invoked when a pong frame is received
   *
   * @param payload a byte array
   */
  default void onPongFrame(byte[] payload) {
  }
}
