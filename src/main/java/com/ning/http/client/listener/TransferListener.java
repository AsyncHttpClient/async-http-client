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
package com.ning.http.client.listener;

import com.ning.http.client.FluentCaseInsensitiveStringsMap;

import java.io.IOException;
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
    public void onBytesReceived(ByteBuffer buffer) throws IOException;

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
