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
package org.asynchttpclient.netty.handler;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.asynchttpclient.ResponseBodyControl;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * Netty implementation of {@link ResponseBodyControl}.
 */
@ApiStatus.Internal
public final class NettyResponseBodyControl implements ResponseBodyControl {

    private static final AttributeKey<NettyResponseBodyControl> ATTRIBUTE =
            AttributeKey.valueOf(NettyResponseBodyControl.class, "control");

    private final Channel channel;
    private final Runnable resumeAction;
    private final Runnable cancelAction;
    private final boolean previousAutoRead;

    private volatile boolean suspended;
    private boolean active = true;

    static NettyResponseBodyControl create(Channel channel, Runnable resumeAction, Runnable cancelAction) {
        if (!channel.eventLoop().inEventLoop()) {
            throw new IllegalStateException("A response body control must be initialized on its channel event loop");
        }
        if (get(channel) != null) {
            throw new IllegalStateException("The channel already has a response body control");
        }

        NettyResponseBodyControl control = new NettyResponseBodyControl(channel, resumeAction, cancelAction);
        channel.attr(ATTRIBUTE).set(control);
        return control;
    }

    static NettyResponseBodyControl get(Channel channel) {
        return channel != null ? channel.attr(ATTRIBUTE).get() : null;
    }

    static void complete(Channel channel) {
        NettyResponseBodyControl control = get(channel);
        if (control != null) {
            control.execute(control::complete0);
        }
    }

    static void discardForChannelClose(Channel channel) {
        NettyResponseBodyControl control = get(channel);
        if (control != null) {
            control.execute(control::discard0);
        }
    }

    /**
     * Returns whether response reads on {@code channel} are suspended by a response body control.
     */
    public static boolean isSuspended(Channel channel) {
        NettyResponseBodyControl control = get(channel);
        return control != null && control.suspended;
    }

    private NettyResponseBodyControl(Channel channel, Runnable resumeAction, Runnable cancelAction) {
        this.channel = Objects.requireNonNull(channel, "channel");
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
        if (active && !suspended) {
            suspended = true;
            channel.config().setAutoRead(false);
        }
    }

    private void resume0() {
        if (!active || !suspended) {
            return;
        }

        suspended = false;
        resumeAction.run();
        if (previousAutoRead) {
            channel.config().setAutoRead(true);
        } else {
            channel.read();
        }
    }

    private void cancel0() {
        if (!active) {
            return;
        }

        detach(false);
        cancelAction.run();
    }

    private void complete0() {
        if (active) {
            detach(true);
        }
    }

    private void discard0() {
        if (active) {
            // The caller is already tearing down the channel, so restoring its read mode has no purpose.
            detach(false);
        }
    }

    private void detach(boolean restoreAutoRead) {
        active = false;
        suspended = false;
        channel.attr(ATTRIBUTE).compareAndSet(this, null);
        if (restoreAutoRead && previousAutoRead && !channel.config().isAutoRead()) {
            channel.config().setAutoRead(true);
        }
    }

    private void execute(Runnable task) {
        if (channel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            channel.eventLoop().execute(task);
        }
    }
}
