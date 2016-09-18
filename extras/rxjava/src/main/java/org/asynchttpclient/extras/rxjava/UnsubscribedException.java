/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.extras.rxjava;

import java.util.concurrent.CancellationException;

/**
 * Indicates that an {@code Observer} unsubscribed during the processing of a HTTP request.
 */
@SuppressWarnings("serial")
public class UnsubscribedException extends CancellationException {

    public UnsubscribedException() {
    }

    public UnsubscribedException(final Throwable cause) {
        initCause(cause);
    }
}
