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

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;

/**
 * An {@link AsyncHandler} which is able to execute WebSocket upgrade. Use the Builder for configuring WebSocket options.
 */
public class WebSocketUpgradeHandler implements UpgradeHandler<WebSocket>, AsyncHandler<WebSocket> {

    private static final int SWITCHING_PROTOCOLS = io.netty.handler.codec.http.HttpResponseStatus.SWITCHING_PROTOCOLS.code();

    private WebSocket webSocket;
    private final List<WebSocketListener> listeners;
    private final AtomicBoolean ok = new AtomicBoolean(false);
    private boolean onSuccessCalled;
    private int status;
    private List<Runnable> bufferedFrames;

    public WebSocketUpgradeHandler(List<WebSocketListener> listeners) {
        this.listeners = listeners;
    }

    public void bufferFrame(Runnable bufferedFrame) {
        if (bufferedFrames == null) {
            bufferedFrames = new ArrayList<>(1);
        }
        bufferedFrames.add(bufferedFrame);
    }

    @Override
    public final void onThrowable(Throwable t) {
        onFailure(t);
    }

    public boolean touchSuccess() {
        boolean prev = onSuccessCalled;
        onSuccessCalled = true;
        return prev;
    }

    @Override
    public final State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
        return State.CONTINUE;
    }

    @Override
    public final State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
        status = responseStatus.getStatusCode();
        return status == SWITCHING_PROTOCOLS ? State.CONTINUE : State.ABORT;
    }

    @Override
    public final State onHeadersReceived(HttpResponseHeaders headers) throws Exception {
        return State.CONTINUE;
    }

    @Override
    public final WebSocket onCompleted() throws Exception {
        if (status != SWITCHING_PROTOCOLS) {
            IllegalStateException e = new IllegalStateException("Invalid Status Code " + status);
            for (WebSocketListener listener : listeners) {
                listener.onError(e);
            }
            throw e;
        }

        return webSocket;
    }

    @Override
    public final void onSuccess(WebSocket webSocket) {
        this.webSocket = webSocket;
        for (WebSocketListener listener : listeners) {
            webSocket.addWebSocketListener(listener);
            listener.onOpen(webSocket);
        }
        if (isNonEmpty(bufferedFrames)) {
            for (Runnable bufferedFrame : bufferedFrames) {
                bufferedFrame.run();
            }
            bufferedFrames = null;
        }
        ok.set(true);
    }

    @Override
    public final void onFailure(Throwable t) {
        for (WebSocketListener listener : listeners) {
            if (!ok.get() && webSocket != null) {
                webSocket.addWebSocketListener(listener);
            }
            listener.onError(t);
        }
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
