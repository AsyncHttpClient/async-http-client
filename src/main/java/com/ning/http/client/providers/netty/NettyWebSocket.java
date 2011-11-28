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
package com.ning.http.client.providers.netty;

import com.ning.http.client.websocket.WebSocket;
import com.ning.http.client.websocket.WebSocketListener;
import org.jboss.netty.channel.Channel;

import java.util.concurrent.ConcurrentLinkedQueue;

public class NettyWebSocket implements WebSocket {

    private final Channel channel;
    private final ConcurrentLinkedQueue<WebSocketListener> listeners = new ConcurrentLinkedQueue<WebSocketListener>();

    public NettyWebSocket(Channel channel) {
        this.channel = channel;
    }

    @Override
    public WebSocket sendMessage(byte[] message) {
        channel.write(message);
        return this;
    }

    @Override
    public WebSocket addMessageListener(WebSocketListener l) {
        listeners.add(l);
        return this;
    }

    @Override
    public void close() {
        onClose();
        channel.close();
    }

    protected void onMessage(byte[] message) {
        for (WebSocketListener l : listeners) {
            l.onMessage(message);
        }
    }

    protected void onError(Throwable t) {
        for (WebSocketListener l : listeners) {
            l.onError(t);
        }
    }

    protected void onClose() {
        for (WebSocketListener l : listeners) {
            l.onClose();
        }
    }
}
