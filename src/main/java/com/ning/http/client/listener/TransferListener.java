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
 *
 */
package com.ning.http.client.listener;

import com.ning.http.client.FluentCaseInsensitiveStringsMap;

import java.nio.ByteBuffer;

/**
 * A simple interface an application can implements in order to received byte transfer information.
 */
public interface TransferListener {

    /**
     * Invoked when the request bytes are starting to get send.
     */
    public void onRequestHeadersSent(FluentCaseInsensitiveStringsMap headers);

    /**
     * Invoked when the response bytes are starting to get received.
     */
    public void onResponseHeadersReceived(FluentCaseInsensitiveStringsMap headers);

    /**
     * Invoked every time response's chunk are received.
     *
     * @param buffer a {@link ByteBuffer}
     */
    public void onBytesReceived(ByteBuffer buffer);

    /**
     * Invoked every time request's chunk are sent.
     *
     * @param buffer a {@link ByteBuffer}
     */
    public void onBytesSent(ByteBuffer buffer);

    /**
     * Invoked when the response bytes are been fully received.
     */
    public void onRequestResponseCompleted();

    /**
     * Invoked when there is an unexpected issue.
     *
     * @param t a {@link Throwable}
     */
    public void onThrowable(Throwable t);
}
