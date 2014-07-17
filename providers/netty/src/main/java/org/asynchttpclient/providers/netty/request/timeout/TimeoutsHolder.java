/*
 * Copyright 2010-2013 Ning, Inc.
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
                requestTimeout = null;
            }
            if (readTimeout != null) {
                readTimeout.cancel();
                readTimeout = null;
            }
        }
    }
}
