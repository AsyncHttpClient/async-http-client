/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.filter;

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Response;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReleasePermitOnCompleteTest {

    @Test
    public void releasesPermitOnceWhenCompletionTriggersThrowable() {
        Semaphore available = new Semaphore(Integer.MAX_VALUE);
        assertTrue(available.tryAcquire());

        AtomicReference<AsyncHandler<Object>> wrapped = new AtomicReference<>();
        AtomicInteger completedCalls = new AtomicInteger();
        AtomicInteger throwableCalls = new AtomicInteger();
        AsyncHandler<Object> handler = new AsyncCompletionHandler<Object>() {
            @Override
            public @Nullable Object onCompleted(@Nullable Response response) {
                completedCalls.incrementAndGet();
                wrapped.get().onThrowable(new RuntimeException("cancelled during completion"));
                return null;
            }

            @Override
            public void onThrowable(Throwable t) {
                throwableCalls.incrementAndGet();
            }
        };
        wrapped.set(ReleasePermitOnComplete.wrap(handler, available));

        assertDoesNotThrow(() -> wrapped.get().onCompleted());
        assertEquals(Integer.MAX_VALUE, available.availablePermits());
        assertEquals(1, completedCalls.get());
        assertEquals(1, throwableCalls.get());
    }
}
