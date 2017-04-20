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

import java.util.ArrayList;
import java.util.List;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.netty.ws.NettyWebSocket;

/**
 * An {@link AsyncHandler} which is able to execute WebSocket upgrade. Use the Builder for configuring WebSocket options.
 */
public class WebSocketUpgradeHandler implements AsyncHandler<NettyWebSocket> {

    private static final int SWITCHING_PROTOCOLS = io.netty.handler.codec.http.HttpResponseStatus.SWITCHING_PROTOCOLS.code();

    private NettyWebSocket webSocket;
    private final List<WebSocketListener> listeners;

    public WebSocketUpgradeHandler(List<WebSocketListener> listeners) {
        this.listeners = listeners;
    }

    @Override
    public final State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
        return responseStatus.getStatusCode() == SWITCHING_PROTOCOLS ? State.CONTINUE : State.ABORT;
    }

    @Override
    public final State onHeadersReceived(HttpHeaders headers) throws Exception {
        return State.CONTINUE;
    }

    @Override
    public final State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
        return State.CONTINUE;
    }

    @Override
    public final NettyWebSocket onCompleted() throws Exception {
        return webSocket;
    }

    @Override
    public final void onThrowable(Throwable t) {
        for (WebSocketListener listener : listeners) {
            if (webSocket != null) {
                webSocket.addWebSocketListener(listener);
            }
            listener.onError(t);
        }
    }

    public final void setWebSocket(NettyWebSocket webSocket) {
        this.webSocket = webSocket;
    }
    
    public final void onOpen() {
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
