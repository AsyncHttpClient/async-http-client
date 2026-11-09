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

import java.util.Objects;

/**
 * Controls transport reads for a response body.
 * <p>
 * The control is thread-safe and remains valid until its response completes. Calls made after completion have no
 * effect.
 * <p>
 * Calls take effect on the event loop that delivers the response body callbacks to the {@link AsyncHandler}. A call
 * made on that event loop, for example from one of those callbacks, takes effect before it returns. A call made from
 * any other thread takes effect asynchronously on the event loop, possibly after further callbacks. A
 * {@link #resume()} always takes effect, even after a {@link #suspend()} that a callback makes before the resume is
 * applied, so a resume is never lost. A {@link #suspend()} from another thread is skipped if, by the time it would take
 * effect, the most recent call to {@link #suspend()} or {@link #resume()} was a resume. Calls made concurrently on
 * different threads are not ordered otherwise.
 * <p>
 * A control is also supplied when the final response headers end the response without a body. In that case,
 * {@link #suspend()} cannot defer completion: the control becomes inactive when
 * {@link AsyncHandler#onResponseBodyStart(ResponseBodyControl)} returns, and later calls have no effect.
 * <p>
 * Unlike calls to the other methods, a task passed to {@link #execute(Runnable)} still runs after completion.
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
     * completion can still arrive while reads are suspended. A transfer coding, such as
     * {@code Transfer-Encoding: gzip}, is decompressed as soon as it is read.
     * <p>
     * If the connection closes while compressed input is held back, the rest of a body that has ended is decompressed
     * and delivered at once, bounded only by {@link AsyncHttpClientConfig#getMaxDecompressedResponseSize()}. That
     * includes a body delimited by the connection, which ends with it; if its compressed stream is incomplete, the
     * request then fails with a {@code DecompressionException}. The rest of a {@code Content-Length} or chunked body
     * that the close cut off is dropped, and the request is retried or fails as for any connection closed before the
     * end of the body. If its last chunk could not be parsed, the request fails with that error instead.
     * <p>
     * After an orderly close by the server (a TCP FIN), native transports such as epoll, io_uring and kqueue read what
     * is left even while reads are suspended. A body without a content coding that arrived before the close is then
     * delivered in full, and compressed input is handled as described above. NIO reads nothing while reads are
     * suspended, so it sees the close only once reads are resumed.
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

    /**
     * Runs {@code task} on the event loop that delivers this response's body, in sequence with the {@link AsyncHandler}
     * callbacks that AHC makes on that event loop, such as
     * {@link AsyncHandler#onBodyPartReceived(HttpResponseBodyPart)}.
     * <p>
     * Called on that event loop, for example from one of those callbacks, the task runs before this method returns.
     * Called from any other thread, the task is queued to the event loop and runs there after the work already pending
     * on it; the event loop can deliver further body parts before the task runs. Either way, the task sees every body
     * part delivered before it runs, and {@link #suspend()}, {@link #resume()} and {@link #cancel()} called from the
     * task are applied before they return, not queued. As {@link #suspend()} says, parts that were already read can
     * still be delivered after it. {@link #resume()} can deliver parts, and {@link #resume()} and {@link #cancel()}
     * can complete the response, before they return.
     * <p>
     * {@link AsyncHandler#onThrowable(Throwable)} runs once the control is inactive, so control calls from a task
     * passed to this method from there have no effect. It runs on the thread that fails the response. For most
     * failures, such as an I/O error, a closed connection or a reset stream, that is this event loop, and the task runs
     * before this method returns. A cancel or abort from another thread calls it on that thread, and a timeout on the
     * client's timer thread unless {@link AsyncHttpClientConfig#isUseEventLoopTimeouts()} is enabled; from there, the
     * task is queued, and can run while that callback is still running.
     * <p>
     * This lets a consumer that buffers body parts on another thread decide whether to resume reads based on everything
     * delivered so far. Calling {@link #resume()} directly from that thread instead acts on a decision that a body part
     * delivered in the meantime can make stale, and can let another read through after the consumer's demand is met.
     * <p>
     * The task can run on a transport thread and must not block. It also runs after the response has completed. If the
     * event loop no longer accepts tasks, for example because it is shutting down, the task does not run.
     * <p>
     * The task must not throw. An exception from a task that runs before this method returns is thrown from this
     * method, so a callback that lets it propagate fails the response. An exception from a queued task is logged by the
     * event loop and does not fail the response. If that task was to resume reads, the response stays suspended until
     * it is resumed or cancelled, or the request timeout fires. With the request timeout disabled, it can stay
     * suspended indefinitely, since the read timeout is paused while reads are suspended.
     * <p>
     * The default implementation runs the task on the calling thread. That suits implementations that apply control
     * calls synchronously on the calling thread; it does not order the task with callbacks made on other threads. An
     * implementation that delegates to another control must delegate this method too, or the task loses the ordering
     * that control provides.
     *
     * @param task the task to run
     * @since 3.0.15
     */
    default void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        task.run();
    }
}
