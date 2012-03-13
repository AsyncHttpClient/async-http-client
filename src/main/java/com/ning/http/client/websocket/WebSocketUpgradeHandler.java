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
package com.ning.http.client.websocket;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.UpgradeHandler;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link AsyncHandler} which is able to execute WebSocket upgrade. Use the Builder for configuring WebSocket options.
 */
public class WebSocketUpgradeHandler implements UpgradeHandler<WebSocket>, AsyncHandler<WebSocket> {

    private WebSocket webSocket;
    private final ConcurrentLinkedQueue<WebSocketListener> l;
    private final String protocol;
    private final long maxByteSize;
    private final long maxTextSize;
    private final AtomicBoolean ok = new AtomicBoolean(false);

    private WebSocketUpgradeHandler(Builder b) {
        l = b.l;
        protocol = b.protocol;
        maxByteSize = b.maxByteSize;
        maxTextSize = b.maxTextSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void onThrowable(Throwable t) {
        onFailure(t);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
        return STATE.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
        if (responseStatus.getStatusCode() == 101) {
            return STATE.UPGRADE;
        } else {
            throw new IllegalStateException("Invalid upgrade protocol, status should be 101 but was " + responseStatus.getStatusCode());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
        return STATE.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final WebSocket onCompleted() throws Exception {
        if (webSocket == null) {
            throw new IllegalStateException("WebSocket is null");
        }
        return webSocket;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void onSuccess(WebSocket webSocket) {
        this.webSocket = webSocket;
        for (WebSocketListener w : l) {
            webSocket.addWebSocketListener(w);
            w.onOpen(webSocket);
        }
        ok.set(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void onFailure(Throwable t) {
        for (WebSocketListener w : l) {
            if (!ok.get() && webSocket != null) {
                webSocket.addWebSocketListener(w);
            }
            w.onError(t);
        }
    }

    /**
     * Build a {@link WebSocketUpgradeHandler}
     */
    public final static class Builder {
        private ConcurrentLinkedQueue<WebSocketListener> l = new ConcurrentLinkedQueue<WebSocketListener>();
        private String protocol = "";
        private long maxByteSize = 8192;
        private long maxTextSize = 8192;

        /**
         * Add a {@link WebSocketListener} that will be added to the {@link WebSocket}
         *
         * @param listener a {@link WebSocketListener}
         * @return this
         */
        public Builder addWebSocketListener(WebSocketListener listener) {
            l.add(listener);
            return this;
        }

        /**
         * Remove a {@link WebSocketListener}
         *
         * @param listener a {@link WebSocketListener}
         * @return this
         */
        public Builder removeWebSocketListener(WebSocketListener listener) {
            l.remove(listener);
            return this;
        }

        /**
         * Set the WebSocket protocol.
         *
         * @param protocol the WebSocket protocol.
         * @return this
         */
        public Builder setProtocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        /**
         * Set the max size of the WebSocket byte message that will be sent.
         *
         * @param maxByteSize max size of the WebSocket byte message
         * @return this
         */
        public Builder setMaxByteSize(long maxByteSize) {
            this.maxByteSize = maxByteSize;
            return this;
        }

        /**
         * Set the max size of the WebSocket text message that will be sent.
         *
         * @param maxTextSize max size of the WebSocket byte message
         * @return this
         */
        public Builder setMaxTextSize(long maxTextSize) {
            this.maxTextSize = maxTextSize;
            return this;
        }

        /**
         * Build a {@link WebSocketUpgradeHandler}
         * @return a {@link WebSocketUpgradeHandler}
         */
        public WebSocketUpgradeHandler build() {
            return new WebSocketUpgradeHandler(this);
        }
    }
}
