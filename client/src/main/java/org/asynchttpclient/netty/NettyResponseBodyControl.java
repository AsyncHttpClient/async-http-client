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

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Netty implementation of {@link ResponseBodyControl}.
 */
@ApiStatus.Internal
public final class NettyResponseBodyControl implements ResponseBodyControl {

    private final NettyResponseFuture<?> future;
    private final Channel channel;
    private final Runnable suspensionStartedAction;
    private final Runnable suspensionEndedAction;
    private final Runnable resumeAction;
    private final Consumer<Boolean> cancelAction;
    private final boolean previousAutoRead;

    private final AtomicBoolean active = new AtomicBoolean(true);
    private volatile boolean suspended;
    private volatile boolean bodyFullyRead;

    public static NettyResponseBodyControl create(NettyResponseFuture<?> future, Channel channel,
                                                  Runnable resumeAction, Consumer<Boolean> cancelAction) {
        return create(future, channel, NettyResponseBodyControl::noop, NettyResponseBodyControl::noop,
                resumeAction, cancelAction);
    }

    public static NettyResponseBodyControl create(NettyResponseFuture<?> future, Channel channel,
                                                  Runnable suspensionStartedAction,
                                                  Runnable suspensionEndedAction,
                                                  Runnable resumeAction, Consumer<Boolean> cancelAction) {
        if (!channel.eventLoop().inEventLoop()) {
            throw new IllegalStateException("A response body control must be initialized on its channel event loop");
        }

        NettyResponseBodyControl control = new NettyResponseBodyControl(
                future, channel, suspensionStartedAction, suspensionEndedAction, resumeAction, cancelAction);
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
        return control != null && control.active.get() && control.suspended;
    }

    /**
     * Returns whether {@code future} is suspended on {@code channel}.
     */
    public static boolean isSuspended(NettyResponseFuture<?> future, Channel channel) {
        NettyResponseBodyControl control = future.responseBodyControl();
        return control != null && control.channel == channel && control.active.get() && control.suspended;
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
                                     Runnable resumeAction, Consumer<Boolean> cancelAction) {
        this.future = Objects.requireNonNull(future, "future");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.suspensionStartedAction = Objects.requireNonNull(suspensionStartedAction, "suspensionStartedAction");
        this.suspensionEndedAction = Objects.requireNonNull(suspensionEndedAction, "suspensionEndedAction");
        this.resumeAction = Objects.requireNonNull(resumeAction, "resumeAction");
        this.cancelAction = Objects.requireNonNull(cancelAction, "cancelAction");
        previousAutoRead = channel.config().isAutoRead();
    }

    @Override
    public void suspend() {
        execute(this::suspend0);
    }

    @Override
    public void resume() {
        execute(this::resume0);
    }

    @Override
    public void cancel() {
        execute(this::cancel0);
    }

    private void suspend0() {
        if (active.get() && !suspended) {
            suspensionStartedAction.run();
            suspended = true;
            channel.config().setAutoRead(false);
        }
    }

    private void resume0() {
        if (!active.get() || !suspended) {
            return;
        }

        endSuspension();
        resumeAction.run();
        if (previousAutoRead) {
            channel.config().setAutoRead(true);
        } else {
            channel.read();
        }
    }

    private void cancel0() {
        if (!active.compareAndSet(true, false)) {
            return;
        }

        future.clearResponseBodyControl(this);
        detach0(bodyFullyRead);
        cancelAction.accept(bodyFullyRead);
    }

    private void deactivate(boolean restoreAutoRead) {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        future.clearResponseBodyControl(this);
        execute(() -> detach0(restoreAutoRead));
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

    private void execute(Runnable task) {
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
