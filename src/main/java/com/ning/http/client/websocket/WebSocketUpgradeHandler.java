/*
 * Copyright (c) 2010-2011 Sonatype, Inc. All rights reserved.
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
import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * An {@link AsyncHandler} which is able to execute WebSocket upgrade.
 */
public class WebSocketUpgradeHandler implements UpgradeHandler<WebSocket>, AsyncHandler<WebSocket> {

    private WebSocket webSocket;

    @Override
    public void onThrowable(Throwable t) {
        onFailure(t);
    }

    @Override
    public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
        return STATE.CONTINUE;
    }

    @Override
    public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
        if (responseStatus.getStatusCode() == 101) {
            return STATE.UPGRADE;
        } else {
           throw new IllegalStateException("Invalid Upgrade protocol");
        }
    }

    @Override
    public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
        return STATE.CONTINUE;
    }

    @Override
    public WebSocket onCompleted() throws Exception {
        if (webSocket == null) {
           throw new IllegalStateException("WebSocket is null");
        }
        return webSocket;
    }

    @Override
    public void onSuccess(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    @Override
    public void onFailure(Throwable t) {
    }

}
