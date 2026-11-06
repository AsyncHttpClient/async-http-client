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
package org.asynchttpclient.netty;

import io.netty.channel.Channel;
import org.asynchttpclient.ResponseBodyControl;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Consumer;

/**
 * Netty implementation of {@link ResponseBodyControl}.
 */
@ApiStatus.Internal
public final class NettyResponseBodyControl implements ResponseBodyControl {

    /**
     * Receives the cancellation of a response body control. One instance can serve every response of a handler, so
     * creating a control does not allocate a callback per response.
     */
    @ApiStatus.Internal
    @FunctionalInterface
    public interface CancelHandler {

        /**
         * Invoked on the channel event loop once the control has been cancelled and detached.
         *
         * @param future        the future the control belonged to
         * @param channel       the channel the control was created for
         * @param bodyFullyRead whether the complete response had been read before the cancellation
         */
        void onCancel(NettyResponseFuture<?> future, Channel channel, boolean bodyFullyRead);
    }

    private static final AtomicIntegerFieldUpdater<NettyResponseBodyControl> ACTIVE_FIELD =
            AtomicIntegerFieldUpdater.newUpdater(NettyResponseBodyControl.class, "active");

    private final NettyResponseFuture<?> future;
    private final Channel channel;
    private final Runnable suspensionStartedAction;
    private final Runnable suspensionEndedAction;
    // Null resumes by touching the future.
    private final @Nullable Runnable resumeAction;
    // Exactly one of the two is set.
    private final @Nullable Consumer<Boolean> cancelAction;
    private final @Nullable CancelHandler cancelHandler;
    private final boolean previousAutoRead;

    private volatile int active = 1;
    private volatile boolean suspendRequested;
    private volatile boolean suspended;
    private volatile boolean bodyFullyRead;

    /**
     * Creates the control for a response whose resumption only touches {@code future}.
     */
    public static NettyResponseBodyControl create(NettyResponseFuture<?> future, Channel channel,
                                                  CancelHandler cancelHandler) {
        return create0(future, channel, NettyResponseBodyControl::noop, NettyResponseBodyControl::noop, null, null,
                Objects.requireNonNull(cancelHandler, "cancelHandler"));
    }

    public static NettyResponseBodyControl create(NettyResponseFuture<?> future, Channel channel,
                                                  Runnable resumeAction, Consumer<Boolean> cancelAction) {
        return create(future, channel, NettyResponseBodyControl::noop, NettyResponseBodyControl::noop,
                resumeAction, cancelAction);
    }

    public static NettyResponseBodyControl create(NettyResponseFuture<?> future, Channel channel,
                                                  Runnable suspensionStartedAction,
                                                  Runnable suspensionEndedAction,
                                                  Runnable resumeAction, Consumer<Boolean> cancelAction) {
        return create0(future, channel, suspensionStartedAction, suspensionEndedAction,
                Objects.requireNonNull(resumeAction, "resumeAction"),
                Objects.requireNonNull(cancelAction, "cancelAction"), null);
    }

    private static NettyResponseBodyControl create0(NettyResponseFuture<?> future, Channel channel,
                                                    Runnable suspensionStartedAction,
                                                    Runnable suspensionEndedAction,
                                                    @Nullable Runnable resumeAction,
                                                    @Nullable Consumer<Boolean> cancelAction,
                                                    @Nullable CancelHandler cancelHandler) {
        if (!channel.eventLoop().inEventLoop()) {
            throw new IllegalStateException("A response body control must be initialized on its channel event loop");
        }

        NettyResponseBodyControl control = new NettyResponseBodyControl(
                future, channel, suspensionStartedAction, suspensionEndedAction, resumeAction, cancelAction,
                cancelHandler);
        NettyResponseBodyControl previous = future.replaceResponseBodyControl(control);
        if (previous != null) {
            previous.deactivate(true);
        }
        return control;
    }

    public static void complete(NettyResponseFuture<?> future) {
        NettyResponseBodyControl control = future.responseBodyControl();
        if (control != null) {
            control.deactivate(true);
        }
    }

    public static void discardForChannelClose(NettyResponseFuture<?> future, Channel channel) {
        NettyResponseBodyControl control = future.responseBodyControl();
        if (control != null && control.channel == channel) {
            control.deactivate(false);
        }
    }

    /**
     * Returns whether response reads for {@code future} are suspended by its current response body control.
     */
    public static boolean isSuspended(NettyResponseFuture<?> future) {
        NettyResponseBodyControl control = future.responseBodyControl();
        return control != null && control.active != 0 && control.suspended;
    }

    /**
     * Returns whether {@code future} is suspended on {@code channel}.
     */
    public static boolean isSuspended(NettyResponseFuture<?> future, Channel channel) {
        NettyResponseBodyControl control = future.responseBodyControl();
        return control != null && control.channel == channel && control.active != 0 && control.suspended;
    }

    /**
     * Records that the complete HTTP/1.1 response has reached the client before its terminal callbacks run.
     */
    public static void markBodyFullyRead(NettyResponseFuture<?> future) {
        NettyResponseBodyControl control = future.responseBodyControl();
        if (control != null) {
            control.bodyFullyRead = true;
        }
    }

    private NettyResponseBodyControl(NettyResponseFuture<?> future, Channel channel,
                                     Runnable suspensionStartedAction, Runnable suspensionEndedAction,
                                     @Nullable Runnable resumeAction, @Nullable Consumer<Boolean> cancelAction,
                                     @Nullable CancelHandler cancelHandler) {
        this.future = Objects.requireNonNull(future, "future");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.suspensionStartedAction = Objects.requireNonNull(suspensionStartedAction, "suspensionStartedAction");
        this.suspensionEndedAction = Objects.requireNonNull(suspensionEndedAction, "suspensionEndedAction");
        this.resumeAction = resumeAction;
        this.cancelAction = cancelAction;
        this.cancelHandler = cancelHandler;
        previousAutoRead = channel.config().isAutoRead();
    }

    @Override
    public void suspend() {
        suspendRequested = true;
        if (channel.eventLoop().inEventLoop()) {
            suspend0();
        } else {
            execute(this::applySuspend);
        }
    }

    @Override
    public void resume() {
        suspendRequested = false;
        execute(this::resume0);
    }

    @Override
    public void cancel() {
        execute(this::cancel0);
    }

    private void applySuspend() {
        // A queued suspend() is skipped if resume() was called after the latest suspend(), so it cannot undo a newer
        // resume(). A resume() is never skipped, so it is not lost to a suspend() that a callback decided on an older
        // view.
        if (suspendRequested) {
            suspend0();
        }
    }

    private void suspend0() {
        if (active != 0 && !suspended) {
            suspensionStartedAction.run();
            suspended = true;
            channel.config().setAutoRead(false);
        }
    }

    private void resume0() {
        if (active == 0 || !suspended) {
            return;
        }

        endSuspension();
        Runnable resumeAction = this.resumeAction;
        if (resumeAction != null) {
            resumeAction.run();
        } else {
            future.touch();
        }
        if (previousAutoRead) {
            channel.config().setAutoRead(true);
        } else {
            channel.read();
        }
    }

    private void cancel0() {
        if (!ACTIVE_FIELD.compareAndSet(this, 1, 0)) {
            return;
        }

        future.clearResponseBodyControl(this);
        boolean bodyFullyRead = this.bodyFullyRead;
        detach0(bodyFullyRead);
        CancelHandler cancelHandler = this.cancelHandler;
        if (cancelHandler != null) {
            cancelHandler.onCancel(future, channel, bodyFullyRead);
        } else {
            Objects.requireNonNull(cancelAction).accept(bodyFullyRead);
        }
    }

    private void deactivate(boolean restoreAutoRead) {
        if (!ACTIVE_FIELD.compareAndSet(this, 1, 0)) {
            return;
        }
        future.clearResponseBodyControl(this);
        if (channel.eventLoop().inEventLoop()) {
            detach0(restoreAutoRead);
        } else {
            execute(() -> detach0(restoreAutoRead));
        }
    }

    private void detach0(boolean restoreAutoRead) {
        endSuspension();
        if (restoreAutoRead && previousAutoRead && !channel.config().isAutoRead()) {
            channel.config().setAutoRead(true);
        }
    }

    private void endSuspension() {
        if (suspended) {
            suspended = false;
            suspensionEndedAction.run();
        }
    }

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (channel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            try {
                channel.eventLoop().execute(task);
            } catch (RejectedExecutionException ignored) {
                // The channel is shutting down, so the control has no transport left to affect.
            }
        }
    }

    private static void noop() {
    }
}
