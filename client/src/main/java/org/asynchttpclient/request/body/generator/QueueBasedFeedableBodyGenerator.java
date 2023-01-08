/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.request.body.generator;

import io.netty.buffer.ByteBuf;
import org.asynchttpclient.request.body.Body;

import java.util.Queue;

public abstract class QueueBasedFeedableBodyGenerator<T extends Queue<BodyChunk>> implements FeedableBodyGenerator {

    protected final T queue;
    private FeedListener listener;

    protected QueueBasedFeedableBodyGenerator(T queue) {
        this.queue = queue;
    }

    @Override
    public Body createBody() {
        return new PushBody(queue);
    }

    protected abstract boolean offer(BodyChunk chunk) throws Exception;

    @Override
    public boolean feed(final ByteBuf buffer, final boolean isLast) throws Exception {
        boolean offered = offer(new BodyChunk(buffer, isLast));
        if (offered && listener != null) {
            listener.onContentAdded();
        }
        return offered;
    }

    @Override
    public void setListener(FeedListener listener) {
        this.listener = listener;
    }
}
