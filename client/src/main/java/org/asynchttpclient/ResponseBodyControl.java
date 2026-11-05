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
 * <p>
 * A control is also supplied when the final response headers end the response without a body. In that case,
 * {@link #suspend()} cannot defer completion: the control becomes inactive when
 * {@link AsyncHandler#onResponseBodyStart(ResponseBodyControl)} returns, and later calls have no effect.
 *
 * @since 3.0.14
 */
public interface ResponseBodyControl {

    /**
     * Stops requesting additional response bytes from the transport. Body parts that were already read may still be
     * delivered to the {@link AsyncHandler}.
     * If the final response headers already ended the response, this call has no effect on completion.
     * <p>
     * For an HTTP/1.1 response whose {@code Content-Encoding} AHC decompresses ({@code gzip}, {@code deflate}, and
     * with Netty 4.2.17 or later {@code br}, {@code zstd} or {@code snappy}), a suspension also stops decompression:
     * once it has taken effect, no further body part with content is delivered until {@link #resume()}. {@code zstd}
     * waits for demand only while the system property {@code io.netty.compression.defaultMaxForwardBytes} is positive,
     * as it is by default. Once the body has been decompressed in full, the final empty part, trailing headers and
     * completion can still arrive while reads are suspended. If the connection closes while compressed input is held
     * back, the rest of it is decompressed and delivered at once, bounded only by
     * {@link AsyncHttpClientConfig#getMaxDecompressedResponseSize()}. A transfer coding, such as
     * {@code Transfer-Encoding: gzip}, is decompressed as soon as it is read.
     * <p>
     * While reads are suspended, the read timeout is paused but the request timeout remains active. If the request
     * timeout is disabled, failing to resume or cancel the response can retain its transport resources indefinitely.
     * <p>
     * For HTTP/2, while any response on a connection is suspended, AHC continues returning connection-level
     * flow-control credit so a suspended stream cannot block sibling streams. Responses on connections with no active
     * suspension retain the normal shared connection-window bound. The per-stream window always applies, so roughly
     * {@link AsyncHttpClientConfig#getHttp2InitialWindowSize()} bytes can be queued for each suspended stream. Aggregate
     * buffering during suspension can therefore scale with the number of concurrent streams. Once the last suspension
     * ends, normal connection accounting resumes; credit already returned and data already queued cannot be revoked.
     * Applications can bound buffering with {@link AsyncHttpClientConfig#getHttp2InitialWindowSize()} and
     * {@link AsyncHttpClientConfig#getHttp2MaxConcurrentStreams()}.
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
