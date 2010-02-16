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
package ning.http.client;

import ning.http.client.providers.NettyAsyncResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;

/**
 * A simple {@link HttpContent} which gets created when an asynchronous response's body is available for processing.
 */
public class HttpResponseBody extends HttpContent {
    private final HttpChunk chunk;

    public HttpResponseBody(NettyAsyncResponse response, HttpChunk chunk) {
        super(response);
        this.chunk = chunk;
    }

    /**
     * Return <tt>true</tt> if the full response's body has been read and this instance will be the last one sent
     * to an {@link AsyncStreamingHandler}
     *
     * @return <tt>true</tt> if the full response's body has been read
     */
    public final boolean isComplete() {
        return chunk.isLast();
    }
}
