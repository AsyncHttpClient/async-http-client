/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.providers.netty.response;

import org.asynchttpclient.HttpResponseBodyPart;

/**
 * A callback class used when an HTTP response body is received.
 */
public abstract class ResponseBodyPart extends HttpResponseBodyPart {

    private final boolean last;
    private boolean closeConnection;

    public ResponseBodyPart(boolean last) {
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
