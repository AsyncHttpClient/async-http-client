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
package com.ning.http.client.ws;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.UpgradeHandler;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link AsyncHandler} which is able to execute WebSocket upgrade. Use the Builder for configuring WebSocket options.
 */
public class WebSocketUpgradeHandler implements UpgradeHandler<WebSocket>, AsyncHandler<WebSocket> {

    private WebSocket webSocket;
    private final List<WebSocketListener> listeners;
    private final AtomicBoolean ok = new AtomicBoolean(false);
    private boolean onSuccessCalled;
    private int status;

    protected WebSocketUpgradeHandler() {
        this.listeners = new LinkedList<>();
    }

    protected WebSocketUpgradeHandler(List<WebSocketListener> listeners) {
        this.listeners = listeners;
    }

    @Override
    public void onThrowable(Throwable t) {
        onFailure(t);
    }

    public boolean touchSuccess() {
        boolean prev = onSuccessCalled;
        onSuccessCalled = true;
        return prev;
    }

    @Override
    public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
        return STATE.CONTINUE;
    }

    @Override
    public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
        status = responseStatus.getStatusCode();
        return status == 101 ? STATE.UPGRADE : STATE.ABORT;
    }

    @Override
    public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
        return STATE.CONTINUE;
    }

    @Override
    public WebSocket onCompleted() throws Exception {

        if (status != 101) {
            IllegalStateException e = new IllegalStateException("Invalid Status Code " + status);
            for (WebSocketListener listener : listeners) {
                listener.onError(e);
            }
            return null;
        }

        if (webSocket == null) {
            throw new IllegalStateException("WebSocket is null");
        }
        return webSocket;
    }

    @Override
    public void onSuccess(WebSocket webSocket) {
        this.webSocket = webSocket;
        for (WebSocketListener listener : listeners) {
            webSocket.addWebSocketListener(listener);
            listener.onOpen(webSocket);
        }
        ok.set(true);
    }

    @Override
    public void onFailure(Throwable t) {
        for (WebSocketListener w : listeners) {
            if (!ok.get() && webSocket != null) {
                webSocket.addWebSocketListener(w);
            }
            w.onError(t);
        }
    }

    public void onClose(WebSocket webSocket, int status, String reasonPhrase) {
        // Connect failure
        if (this.webSocket == null) this.webSocket = webSocket;

        for (WebSocketListener listener : listeners) {
            if (webSocket != null) {
                webSocket.addWebSocketListener(listener);
            }
            listener.onClose(webSocket);
            if (listener instanceof WebSocketCloseCodeReasonListener) {
                WebSocketCloseCodeReasonListener.class.cast(listener).onClose(webSocket, status, reasonPhrase);
            }
        }
    }

    /**
     * Build a {@link WebSocketUpgradeHandler}
     */
    public final static class Builder {

        private List<WebSocketListener> listeners = new ArrayList<WebSocketListener>(1);

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
