/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.request.body.generator;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class BlockingFeedableBodyGenerator extends QueueBasedFeedableBodyGenerator<BlockingQueue<BodyChunk>> {
    private final BlockingQueue<BodyChunk> queue;

    public BlockingFeedableBodyGenerator(int capacity) {
        queue = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    protected boolean offer(BodyChunk chunk) throws InterruptedException {
        queue.put(chunk);
        return true;
    }

    @Override
    protected Queue<org.asynchttpclient.request.body.generator.BodyChunk> queue() {
        return queue;
    }
}
