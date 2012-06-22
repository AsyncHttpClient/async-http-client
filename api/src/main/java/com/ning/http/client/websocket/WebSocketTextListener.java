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

/**
 * A {@link WebSocketListener} for text message
 */
public interface WebSocketTextListener extends WebSocketListener {

    /**
     * Invoked when WebSocket text message are received.
     * @param message a {@link String} message
     */
    void onMessage(String message);

    /**
     * Invoked when WebSocket text fragments are received.
     *
     * @param fragment text fragment
     * @param last if this fragment is the last of the series.
     */
    void onFragment(String fragment, boolean last);

}
