/*
 * Copyright (c) 2013 Sonatype, Inc. All rights reserved.
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

package org.asynchttpclient.providers.grizzly.websocket;

import org.asynchttpclient.websocket.WebSocketByteListener;
import org.asynchttpclient.websocket.WebSocketCloseCodeReasonListener;
import org.asynchttpclient.websocket.WebSocketListener;
import org.asynchttpclient.websocket.WebSocketPingListener;
import org.asynchttpclient.websocket.WebSocketPongListener;
import org.asynchttpclient.websocket.WebSocketTextListener;
import org.glassfish.grizzly.websockets.ClosingFrame;
import org.glassfish.grizzly.websockets.DataFrame;

import java.io.ByteArrayOutputStream;

final class AHCWebSocketListenerAdapter implements org.glassfish.grizzly.websockets.WebSocketListener {

    private final WebSocketListener ahcListener;
    private final GrizzlyWebSocketAdapter webSocket;
    private final StringBuilder stringBuffer;
    private final ByteArrayOutputStream byteArrayOutputStream;

    // -------------------------------------------------------- Constructors

    public AHCWebSocketListenerAdapter(final WebSocketListener ahcListener, final GrizzlyWebSocketAdapter webSocket) {
        this.ahcListener = ahcListener;
        this.webSocket = webSocket;
        if (webSocket.bufferFragments) {
            stringBuffer = new StringBuilder();
            byteArrayOutputStream = new ByteArrayOutputStream();
        } else {
            stringBuffer = null;
            byteArrayOutputStream = null;
        }
    }

    // ------------------------------ Methods from Grizzly WebSocketListener

    @Override
    public void onClose(org.glassfish.grizzly.websockets.WebSocket gWebSocket, DataFrame dataFrame) {
        try {
            if (ahcListener instanceof WebSocketCloseCodeReasonListener) {
                ClosingFrame cf = ClosingFrame.class.cast(dataFrame);
                WebSocketCloseCodeReasonListener.class.cast(ahcListener).onClose(webSocket, cf.getCode(), cf.getReason());
            } else {
                ahcListener.onClose(webSocket);
            }
        } catch (Throwable e) {
            ahcListener.onError(e);
        }
    }

    @Override
    public void onConnect(org.glassfish.grizzly.websockets.WebSocket gWebSocket) {
        try {
            ahcListener.onOpen(webSocket);
        } catch (Throwable e) {
            ahcListener.onError(e);
        }
    }

    @Override
    public void onMessage(org.glassfish.grizzly.websockets.WebSocket webSocket, String s) {
        try {
            if (ahcListener instanceof WebSocketTextListener) {
                WebSocketTextListener.class.cast(ahcListener).onMessage(s);
            }
        } catch (Throwable e) {
            ahcListener.onError(e);
        }
    }

    @Override
    public void onMessage(org.glassfish.grizzly.websockets.WebSocket webSocket, byte[] bytes) {
        try {
            if (ahcListener instanceof WebSocketByteListener) {
                WebSocketByteListener.class.cast(ahcListener).onMessage(bytes);
            }
        } catch (Throwable e) {
            ahcListener.onError(e);
        }
    }

    @Override
    public void onPing(org.glassfish.grizzly.websockets.WebSocket webSocket, byte[] bytes) {
        try {
            if (ahcListener instanceof WebSocketPingListener) {
                WebSocketPingListener.class.cast(ahcListener).onPing(bytes);
            }
        } catch (Throwable e) {
            ahcListener.onError(e);
        }
    }

    @Override
    public void onPong(org.glassfish.grizzly.websockets.WebSocket webSocket, byte[] bytes) {
        try {
            if (ahcListener instanceof WebSocketPongListener) {
                WebSocketPongListener.class.cast(ahcListener).onPong(bytes);
            }
        } catch (Throwable e) {
            ahcListener.onError(e);
        }
    }

    @Override
    public void onFragment(org.glassfish.grizzly.websockets.WebSocket webSocket, String s, boolean last) {
        try {
            if (this.webSocket.bufferFragments) {
                synchronized (this.webSocket) {
                    stringBuffer.append(s);
                    if (last) {
                        if (ahcListener instanceof WebSocketTextListener) {
                            final String message = stringBuffer.toString();
                            stringBuffer.setLength(0);
                            WebSocketTextListener.class.cast(ahcListener).onMessage(message);
                        }
                    }
                }
            }
        } catch (Throwable e) {
            ahcListener.onError(e);
        }
    }

    @Override
    public void onFragment(org.glassfish.grizzly.websockets.WebSocket webSocket, byte[] bytes, boolean last) {
        try {
            if (this.webSocket.bufferFragments) {
                synchronized (this.webSocket) {
                    byteArrayOutputStream.write(bytes);
                    if (last) {
                        if (ahcListener instanceof WebSocketByteListener) {
                            final byte[] bytesLocal = byteArrayOutputStream.toByteArray();
                            byteArrayOutputStream.reset();
                            WebSocketByteListener.class.cast(ahcListener).onMessage(bytesLocal);
                        }
                    }
                }
            }
        } catch (Throwable e) {
            ahcListener.onError(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        AHCWebSocketListenerAdapter that = (AHCWebSocketListenerAdapter) o;

        if (ahcListener != null ? !ahcListener.equals(that.ahcListener) : that.ahcListener != null)
            return false;
        //noinspection RedundantIfStatement
        if (webSocket != null ? !webSocket.equals(that.webSocket) : that.webSocket != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = ahcListener != null ? ahcListener.hashCode() : 0;
        result = 31 * result + (webSocket != null ? webSocket.hashCode() : 0);
        return result;
    }
} // END AHCWebSocketListenerAdapter
