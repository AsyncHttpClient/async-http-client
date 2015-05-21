/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.providers.netty.request.timeout;

import io.netty.util.Timeout;

import java.util.concurrent.atomic.AtomicBoolean;

public class TimeoutsHolder {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    public volatile Timeout requestTimeout;
    public volatile Timeout readTimeout;

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            if (requestTimeout != null) {
                requestTimeout.cancel();
                RequestTimeoutTimerTask.class.cast(requestTimeout.task()).clean();
                requestTimeout = null;
            }
            if (readTimeout != null) {
                readTimeout.cancel();
                ReadTimeoutTimerTask.class.cast(readTimeout.task()).clean();
                readTimeout = null;
            }
        }
    }
}
