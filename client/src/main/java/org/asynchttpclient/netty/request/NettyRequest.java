/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.request;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import org.asynchttpclient.netty.request.body.NettyBody;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public final class NettyRequest {

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<NettyRequest> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(NettyRequest.class, "state");

    // Single atomic state machine guarding who releases httpRequest (and the request-body ByteBuf it holds),
    // so it is released EXACTLY once — no double-free, no leak — even when an abort/cancel/timeout on one
    // thread races the channel write on the event loop. The two transitions out of OWNED_BY_AHC are mutually
    // exclusive (CAS), so exactly one of {Netty's encoder, AHC} ends up owning the release.
    private static final int OWNED_BY_AHC = 0;       // initial: AHC owns the release
    private static final int HANDED_TO_CHANNEL = 1;  // handed to Netty's HTTP/1.1 encoder; IT releases
    private static final int RELEASED = 2;           // AHC released it

    private final HttpRequest httpRequest;
    private final NettyBody body;
    @SuppressWarnings("unused")
    private volatile int state;

    NettyRequest(HttpRequest httpRequest, NettyBody body) {
        this.httpRequest = httpRequest;
        this.body = body;
    }

    public HttpRequest getHttpRequest() {
        return httpRequest;
    }

    public NettyBody getBody() {
        return body;
    }

    /**
     * Releases the underlying HTTP/1.1 {@link HttpRequest} (and the request-body {@link io.netty.buffer.ByteBuf}
     * it holds) — but only while AsyncHttpClient still owns it (it was never handed to a channel write). Once
     * {@link #markHandedToChannel()} has claimed it, Netty's encoder owns the release and this becomes a no-op,
     * so the two can never double-free. The atomic CAS also closes the check-then-release TOCTOU an external
     * {@code isHandedToChannel()} test would leave between an abort thread and the event-loop write.
     * <p>
     * In the HTTP/2 path the {@code httpRequest} object is never written to a channel — its content is
     * re-encoded as HTTP/2 frames — so AHC always owns its release and this performs it. The early-abort paths
     * (a draining/closed/queued-then-dropped connection, or a crashing {@code onRequestSend}) call it too.
     * Idempotent and thread-safe — extra calls are no-ops.
     */
    public void release() {
        if (STATE_UPDATER.compareAndSet(this, OWNED_BY_AHC, RELEASED)) {
            ReferenceCountUtil.release(httpRequest);
        }
    }

    /**
     * Atomically claims {@link #getHttpRequest()} for a channel write (the HTTP/1.1 path), after which Netty's
     * encoder owns its release. Returns {@code true} if the caller may proceed with the write; returns
     * {@code false} when a concurrent abort/cancel/timeout has ALREADY released the request body — in which case
     * the caller MUST NOT write the now-freed buffer. This single CAS resolves the double-free/use-after-free
     * race a separate {@code handedToChannel} flag plus a non-atomic {@code release()} would otherwise allow
     * (e.g. a {@code setBody(ByteBuf)} request cancelled on one thread while the event loop writes it).
     */
    public boolean markHandedToChannel() {
        return STATE_UPDATER.compareAndSet(this, OWNED_BY_AHC, HANDED_TO_CHANNEL);
    }

    public boolean isHandedToChannel() {
        return state == HANDED_TO_CHANNEL;
    }
}
