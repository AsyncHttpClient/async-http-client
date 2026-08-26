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
package org.asynchttpclient;

/**
 * Controls transport reads for a response body.
 * <p>
 * The control is thread-safe and remains valid until its response completes. Calls made after completion have no
 * effect.
 *
 * @since 3.0.14
 */
public interface ResponseBodyControl {

    /**
     * Stops requesting additional response bytes from the transport. Body parts that were already read may still be
     * delivered to the {@link AsyncHandler}.
     * <p>
     * While reads are suspended, the read timeout is paused but the request timeout remains active. If the request
     * timeout is disabled, failing to resume or cancel the response can retain its transport resources indefinitely.
     */
    void suspend();

    /**
     * Resumes requesting response bytes after a call to {@link #suspend()}.
     */
    void resume();

    /**
     * Stops processing the response body. As with {@link AsyncHandler.State#ABORT}, the handler is completed normally.
     * Returning {@code ABORT} is the preferred way to stop from within an {@link AsyncHandler} callback; this method is
     * intended for cancellation after the callback has returned, including from another thread.
     */
    void cancel();
}
