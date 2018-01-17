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

import io.netty.buffer.ByteBuf;
import org.asynchttpclient.request.body.Body;

import java.util.Queue;

public abstract class QueueBasedFeedableBodyGenerator<T extends Queue<BodyChunk>> implements FeedableBodyGenerator {

  protected final T queue;
  private FeedListener listener;

  public QueueBasedFeedableBodyGenerator(T queue) {
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
