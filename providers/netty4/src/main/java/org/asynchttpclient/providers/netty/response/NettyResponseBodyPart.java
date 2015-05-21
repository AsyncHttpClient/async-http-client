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
package org.asynchttpclient.providers.netty.response;

import org.asynchttpclient.HttpResponseBodyPart;

/**
 * A callback class used when an HTTP response body is received.
 */
public abstract class NettyResponseBodyPart extends HttpResponseBodyPart {

    private final boolean last;
    private boolean closeConnection;

    public NettyResponseBodyPart(boolean last) {
        this.last = last;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isLast() {
        return last;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void markUnderlyingConnectionAsToBeClosed() {
        closeConnection = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isUnderlyingConnectionToBeClosed() {
        return closeConnection;
    }
}
