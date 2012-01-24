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
 * A {@link WebSocketListener} for bytes
 */
public interface WebSocketByteListener extends WebSocketListener {

    /**
     * Invoked when bytes are available.
     * @param message a byte array.
     */
    void onMessage(byte[] message);


    /**
     * Invoked when bytes of a fragmented message are available.
     *
     * @param fragment byte[] fragment.
     * @param last if this fragment is the last in the series.
     */
    void onFragment(byte[] fragment, boolean last);

}
