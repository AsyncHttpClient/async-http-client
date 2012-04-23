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
 * Extend the normal close listener with one that support the WebSocket's code and reason.
 * @See http://tools.ietf.org/html/rfc6455#section-5.5.1
 */
public interface WebSocketCloseCodeReasonListener {

    /**
     * Invoked when the {@link WebSocket} is close.
     *
     * @param websocket
     */
    void onClose(WebSocket websocket, int code, String reason);
}
