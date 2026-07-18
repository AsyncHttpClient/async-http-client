/*
 *    Copyright (c) 2018-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.channel;

import java.io.IOException;

/**
 * Connections limiter.
 */
public interface ConnectionSemaphore {

    void acquireChannelLock(Object partitionKey) throws IOException;

    /**
     * Acquires a connection permit for {@code partitionKey}, optionally without blocking.
     *
     * <p>When {@code nonBlocking} is {@code true} a permit must be taken without waiting — the
     * too-many-connections exception is thrown immediately if none is free. This is used when the caller
     * runs on a Netty event-loop thread (a redirect / 401 / 407 / retry replay re-enters the send path on
     * the event loop), where waiting for the configured acquire timeout would freeze the loop and stall
     * every other connection it serves. When {@code false} this behaves exactly like
     * {@link #acquireChannelLock(Object)}.
     *
     * <p>The default implementation ignores the hint and delegates to the blocking
     * {@link #acquireChannelLock(Object)}, preserving the behaviour of custom implementations; the built-in
     * limiters override it to honour {@code nonBlocking}.
     *
     * @param partitionKey the per-host partition key the permit is scoped to
     * @param nonBlocking  {@code true} to fail fast instead of waiting for a permit
     * @throws IOException if no permit could be acquired
     */
    default void acquireChannelLock(Object partitionKey, boolean nonBlocking) throws IOException {
        acquireChannelLock(partitionKey);
    }

    void releaseChannelLock(Object partitionKey);
}
