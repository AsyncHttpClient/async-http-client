/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package com.ning.http.client.providers.netty.ws;

import org.jboss.netty.channel.Channel;

import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.websocket.WebSocketByteListener;
import com.ning.http.client.websocket.WebSocketListener;
import com.ning.http.client.websocket.WebSocketTextListener;
import com.ning.http.util.StandardCharsets;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DefaultNettyWebSocket extends NettyWebSocket {

    private final StringBuilder textBuffer = new StringBuilder();
    private final ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();

    public DefaultNettyWebSocket(Channel channel) {
        super(channel, new ConcurrentLinkedQueue<WebSocketListener>());
    }

    public void onBinaryFragment(HttpResponseBodyPart part) {

        boolean last = part.isLast();
        byte[] message = part.getBodyPartBytes();

        if (!last) {
            try {
                byteBuffer.write(message);
            } catch (Exception ex) {
                byteBuffer.reset();
                onError(ex);
                return;
            }

            if (byteBuffer.size() > maxBufferSize) {
                byteBuffer.reset();
                Exception e = new Exception("Exceeded Netty Web Socket maximum buffer size of " + maxBufferSize);
                onError(e);
                close();
                return;
            }
        }

        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketByteListener) {
                WebSocketByteListener byteListener = (WebSocketByteListener) listener;
                try {
                    if (!last) {
                        byteListener.onFragment(message, last);
                    } else if (byteBuffer.size() > 0) {
                        byteBuffer.write(message);
                        byteListener.onFragment(message, last);
                        byteListener.onMessage(byteBuffer.toByteArray());
                    } else {
                        byteListener.onMessage(message);
                    }
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        }

        if (last) {
            byteBuffer.reset();
        }
    }

    public void onTextFragment(HttpResponseBodyPart part) {

        boolean last = part.isLast();
        // FIXME this is so wrong! there's a chance the fragment is not valid UTF-8 because a char is truncated
        String message = new String(part.getBodyPartBytes(), StandardCharsets.UTF_8);

        if (!last) {
            textBuffer.append(message);

            if (textBuffer.length() > maxBufferSize) {
                textBuffer.setLength(0);
                Exception e = new Exception("Exceeded Netty Web Socket maximum buffer size of " + maxBufferSize);
                onError(e);
                close();
                return;
            }
        }

        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketTextListener) {
                WebSocketTextListener textlistener = (WebSocketTextListener) listener;
                try {
                    if (!last) {
                        textlistener.onFragment(message, last);
                    } else if (textBuffer.length() > 0) {
                        textlistener.onFragment(message, last);
                        textlistener.onMessage(textBuffer.append(message).toString());
                    } else {
                        textlistener.onMessage(message);
                    }
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        }

        if (last) {
            textBuffer.setLength(0);
        }
    }
}
