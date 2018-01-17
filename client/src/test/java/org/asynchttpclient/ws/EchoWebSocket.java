/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

import java.io.IOException;
import java.nio.ByteBuffer;

public class EchoWebSocket extends WebSocketAdapter {

  @Override
  public void onWebSocketConnect(Session sess) {
    super.onWebSocketConnect(sess);
    sess.setIdleTimeout(10000);
  }

  @Override
  public void onWebSocketClose(int statusCode, String reason) {
    getSession().close();
    super.onWebSocketClose(statusCode, reason);
  }

  @Override
  public void onWebSocketBinary(byte[] payload, int offset, int len) {
    if (isNotConnected()) {
      return;
    }
    try {
      getRemote().sendBytes(ByteBuffer.wrap(payload, offset, len));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  public void onWebSocketText(String message) {
    if (isNotConnected()) {
      return;
    }
    try {
      if (message.equals("CLOSE"))
        getSession().close();
      else
        getRemote().sendString(message);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
