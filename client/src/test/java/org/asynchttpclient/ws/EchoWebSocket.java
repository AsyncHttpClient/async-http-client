/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.ws;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;

public class EchoWebSocket extends WebSocketAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(EchoWebSocket.class);

    @Override
    public void onWebSocketConnect(Session sess) {
        super.onWebSocketConnect(sess);
        sess.setIdleTimeout(Duration.ofMillis(10_000));
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
            LOGGER.debug("Received binary frame of size {}: {}", len, new String(payload, offset, len, UTF_8));
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

        if ("CLOSE".equals(message)) {
            getSession().close();
            return;
        }

        try {
            LOGGER.debug("Received text frame of size: {}", message);
            getRemote().sendString(message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
