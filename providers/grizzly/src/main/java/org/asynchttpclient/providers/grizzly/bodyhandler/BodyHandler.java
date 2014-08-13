/*
 * Copyright (c) 2013-2014 Sonatype, Inc. All rights reserved.
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

package org.asynchttpclient.providers.grizzly.bodyhandler;

import org.asynchttpclient.Request;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpRequestPacket;

import java.io.IOException;

public abstract class BodyHandler {

    public static int MAX_CHUNK_SIZE = 8192;

    public abstract boolean handlesBodyType(final Request request);

    public abstract boolean doHandle(final FilterChainContext ctx,
            final Request request, final HttpRequestPacket requestPacket)
            throws IOException;
    
    /**
     * Tries to predict request content-length based on the {@link Request}.
     * Not all the <tt>BodyHandler</tt>s can predict the content-length in advance.
     * 
     * @param request
     * @return the content-length, or <tt>-1</tt> if the content-length can't be
     * predicted
     */
    protected long getContentLength(final Request request) {
        return request.getContentLength();
    }
}
