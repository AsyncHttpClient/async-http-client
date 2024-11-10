/*
 *    Copyright (c) 2017-2023 AsyncHttpClient Project. All rights reserved.
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

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.netty.ws.NettyWebSocket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.SWITCHING_PROTOCOLS_101;

/**
 * An {@link AsyncHandler} which is able to execute WebSocket upgrade. Use the Builder for configuring WebSocket options.
 */
public class WebSocketUpgradeHandler implements AsyncHandler<NettyWebSocket> {

    private final List<WebSocketListener> listeners;
    private @Nullable NettyWebSocket webSocket;

    public WebSocketUpgradeHandler(List<WebSocketListener> listeners) {
        this.listeners = listeners;
    }

    protected void setWebSocket0(NettyWebSocket webSocket) {
    }

    protected void onStatusReceived0(HttpResponseStatus responseStatus) throws Exception {
    }

    protected void onHeadersReceived0(HttpHeaders headers) throws Exception {
    }

    protected void onBodyPartReceived0(HttpResponseBodyPart bodyPart) throws Exception {
    }

    protected void onCompleted0() throws Exception {
    }

    protected void onThrowable0(Throwable t) {
    }

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
    public final @Nullable NettyWebSocket onCompleted() throws Exception {
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

    /**
     * @param webSocket this parameter is the same object as the field webSocket,
     *                  but guaranteed to be not null. This is done to satisfy NullAway requirements
     */
    public final void onOpen(NettyWebSocket webSocket) {
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
    public static final class Builder {

        private final List<WebSocketListener> listeners = new ArrayList<>(1);

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
