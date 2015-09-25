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
package org.asynchttpclient.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread factory that generates thread names by adding incrementing number
 * to the specified prefix.
 *
 * @author Stepan Koltsov
 */
public class PrefixIncrementThreadFactory implements ThreadFactory {
    private final String namePrefix;
    private final AtomicInteger threadNumber = new AtomicInteger();

    public PrefixIncrementThreadFactory(String namePrefix) {
        if (namePrefix == null || namePrefix.isEmpty()) {
            throw new IllegalArgumentException("namePrefix must not be empty");
        }
        this.namePrefix = namePrefix;
    }

    public Thread newThread(Runnable r) {
        return new Thread(r, namePrefix + threadNumber.incrementAndGet());
    }
}
