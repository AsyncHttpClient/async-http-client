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

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.netty.ws.NettyWebSocket;

import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.SWITCHING_PROTOCOLS_101;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@link AsyncHandler} that handles WebSocket protocol upgrade and connection management.
 * <p>
 * This handler manages the HTTP-to-WebSocket upgrade process by:
 * </p>
 * <ul>
 *   <li>Validating the HTTP 101 Switching Protocols response</li>
 *   <li>Processing the upgrade headers</li>
 *   <li>Establishing the WebSocket connection</li>
 *   <li>Notifying registered listeners of connection events</li>
 * </ul>
 * <p>
 * Subclasses can override the protected hook methods (*0) to add custom behavior
 * at various stages of the upgrade process.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * WebSocketUpgradeHandler handler = new WebSocketUpgradeHandler.Builder()
 *     .addWebSocketListener(new WebSocketListener() {
 *         @Override
 *         public void onOpen(WebSocket websocket) {
 *             websocket.sendTextFrame("Hello!");
 *         }
 *
 *         @Override
 *         public void onTextFrame(String payload, boolean finalFragment, int rsv) {
 *             System.out.println("Received: " + payload);
 *         }
 *
 *         @Override
 *         public void onClose(WebSocket websocket, int code, String reason) {
 *             System.out.println("Connection closed");
 *         }
 *
 *         @Override
 *         public void onError(Throwable t) {
 *             System.err.println("Error: " + t.getMessage());
 *         }
 *     })
 *     .build();
 *
 * WebSocket websocket = asyncHttpClient
 *     .prepareGet("ws://echo.websocket.org")
 *     .execute(handler)
 *     .get();
 * }</pre>
 */
public class WebSocketUpgradeHandler implements AsyncHandler<NettyWebSocket> {

  private final List<WebSocketListener> listeners;
  private NettyWebSocket webSocket;

  /**
   * Creates a new WebSocket upgrade handler with the specified listeners.
   *
   * @param listeners the list of WebSocket listeners to notify of events
   */
  public WebSocketUpgradeHandler(List<WebSocketListener> listeners) {
    this.listeners = listeners;
  }

  /**
   * Extension point called when the WebSocket instance is set.
   * <p>
   * Subclasses can override this method to perform additional initialization
   * or configuration when the WebSocket connection is established.
   * </p>
   *
   * @param webSocket the WebSocket instance
   */
  protected void setWebSocket0(NettyWebSocket webSocket) {
  }

  /**
   * Extension point called when the HTTP response status is received.
   * <p>
   * Subclasses can override this method to inspect or validate the status
   * before the upgrade proceeds.
   * </p>
   *
   * @param responseStatus the HTTP response status
   * @throws Exception if an error occurs during status processing
   */
  protected void onStatusReceived0(HttpResponseStatus responseStatus) throws Exception {
  }

  /**
   * Extension point called when the HTTP response headers are received.
   * <p>
   * Subclasses can override this method to inspect or validate the upgrade
   * headers before the connection is established.
   * </p>
   *
   * @param headers the HTTP response headers
   * @throws Exception if an error occurs during header processing
   */
  protected void onHeadersReceived0(HttpHeaders headers) throws Exception {
  }

  /**
   * Extension point called when the HTTP response body part is received.
   * <p>
   * Subclasses can override this method to process any response body data
   * received during the upgrade (though typically there isn't any).
   * </p>
   *
   * @param bodyPart the HTTP response body part
   * @throws Exception if an error occurs during body part processing
   */
  protected void onBodyPartReceived0(HttpResponseBodyPart bodyPart) throws Exception {
  }

  /**
   * Extension point called when the upgrade completes successfully.
   * <p>
   * Subclasses can override this method to perform actions after the
   * WebSocket connection is fully established.
   * </p>
   *
   * @throws Exception if an error occurs during completion processing
   */
  protected void onCompleted0() throws Exception {
  }

  /**
   * Extension point called when an error occurs during the upgrade.
   * <p>
   * Subclasses can override this method to add custom error handling
   * logic before listeners are notified.
   * </p>
   *
   * @param t the Throwable that caused the error
   */
  protected void onThrowable0(Throwable t) {
  }

  /**
   * Extension point called when the WebSocket connection is opened.
   * <p>
   * Subclasses can override this method to perform actions when the
   * connection is ready to send and receive frames.
   * </p>
   */
  protected void onOpen0() {
  }

  @Override
  public final State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
    onStatusReceived0(responseStatus);
    return responseStatus.getStatusCode() == SWITCHING_PROTOCOLS_101 ? State.CONTINUE : State.ABORT;
  }

  @Override
  public final State onHeadersReceived(HttpHeaders headers) throws Exception {
    onHeadersReceived0(headers);
    return State.CONTINUE;
  }

  @Override
  public final State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
    onBodyPartReceived0(bodyPart);
    return State.CONTINUE;
  }

  @Override
  public final NettyWebSocket onCompleted() throws Exception {
    onCompleted0();
    return webSocket;
  }

  @Override
  public final void onThrowable(Throwable t) {
    onThrowable0(t);
    for (WebSocketListener listener : listeners) {
      if (webSocket != null) {
        webSocket.addWebSocketListener(listener);
      }
      listener.onError(t);
    }
  }

  public final void setWebSocket(NettyWebSocket webSocket) {
    this.webSocket = webSocket;
    setWebSocket0(webSocket);
  }

  public final void onOpen() {
    onOpen0();
    for (WebSocketListener listener : listeners) {
      webSocket.addWebSocketListener(listener);
      listener.onOpen(webSocket);
    }
    webSocket.processBufferedFrames();
  }

  /**
   * Build a {@link WebSocketUpgradeHandler}
   */
  public final static class Builder {

    private List<WebSocketListener> listeners = new ArrayList<>(1);

    /**
     * Add a {@link WebSocketListener} that will be added to the {@link WebSocket}
     *
     * @param listener a {@link WebSocketListener}
     * @return this
     */
    public Builder addWebSocketListener(WebSocketListener listener) {
      listeners.add(listener);
      return this;
    }

    /**
     * Remove a {@link WebSocketListener}
     *
     * @param listener a {@link WebSocketListener}
     * @return this
     */
    public Builder removeWebSocketListener(WebSocketListener listener) {
      listeners.remove(listener);
      return this;
    }

    /**
     * Build a {@link WebSocketUpgradeHandler}
     *
     * @return a {@link WebSocketUpgradeHandler}
     */
    public WebSocketUpgradeHandler build() {
      return new WebSocketUpgradeHandler(listeners);
    }
  }
}
