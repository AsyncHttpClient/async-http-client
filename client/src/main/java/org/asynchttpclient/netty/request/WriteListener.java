/*
 *    Copyright (c) 2016-2023 AsyncHttpClient Project. All rights reserved.
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

import io.netty.channel.Channel;
import org.asynchttpclient.handler.ProgressAsyncHandler;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelState;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.future.StackTraceInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.nio.channels.ClosedChannelException;

public abstract class WriteListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(WriteListener.class);
    protected final NettyResponseFuture<?> future;
    final ProgressAsyncHandler<?> progressAsyncHandler;
    final boolean notifyHeaders;

    WriteListener(NettyResponseFuture<?> future, boolean notifyHeaders) {
        this.future = future;
        progressAsyncHandler = future.getAsyncHandler() instanceof ProgressAsyncHandler ? (ProgressAsyncHandler<?>) future.getAsyncHandler() : null;
        this.notifyHeaders = notifyHeaders;
    }

    private void abortOnThrowable(Channel channel, Throwable cause) {
        if (future.getChannelState() == ChannelState.POOLED && (cause instanceof IllegalStateException ||
                cause instanceof ClosedChannelException ||
                cause instanceof SSLException ||
                StackTraceInspector.recoverOnReadOrWriteException(cause))) {
            LOGGER.debug("Write exception on pooled channel, letting retry trigger", cause);
        } else {
            future.abort(cause);
        }
        Channels.silentlyCloseChannel(channel);
    }

    void operationComplete(Channel channel, Throwable cause) {
        future.touch();

        // The write operation failed. If the channel was pooled, it means it got asynchronously closed.
        // Let's retry a second time.
        if (cause != null) {
            abortOnThrowable(channel, cause);
            return;
        }

        if (progressAsyncHandler != null) {
            // We need to make sure we aren't in the middle of an authorization process before publishing events as we will re-publish again the same event after the authorization,
            // causing unpredictable behavior.
            boolean startPublishing = !future.isInAuth() && !future.isInProxyAuth();
            if (startPublishing) {

                if (notifyHeaders) {
                    progressAsyncHandler.onHeadersWritten();
                } else {
                    progressAsyncHandler.onContentWritten();
                }
            }
        }
    }
}
