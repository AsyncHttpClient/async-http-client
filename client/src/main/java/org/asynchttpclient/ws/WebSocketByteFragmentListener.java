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
package org.asynchttpclient.ws;

import org.asynchttpclient.HttpResponseBodyPart;

/**
 * Invoked when WebSocket binary fragments are received.
 * 
 * Actually doesn't do anything, as chunks as assembled into full WebSocket frames.
 * Will be removed in 2.1.
 */
@Deprecated
public interface WebSocketByteFragmentListener extends WebSocketListener {

    /**
     * @param fragment a fragment
     */
    void onFragment(HttpResponseBodyPart fragment);
}
